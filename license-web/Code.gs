/**
 * EggVyapar license keys — the owner's private web app (Google Apps Script, bound to a Google Sheet).
 *
 * Deploy as a web app with "Execute as: Me" and "Who has access: Only myself": Google then lets only the
 * owner's account open it. The Sheet is the registry (tabs "Licenses" and "Devices"); the private signing
 * key is kept in Script Properties (SIGNING_KEY_PKCS8), never in the Sheet. Keys are signed in the
 * owner's browser with WebCrypto (ECDSA P-256 / SHA-256) in exactly the format the app verifies.
 */

var LICENSES = 'Licenses';
var DEVICES = 'Devices';
var LICENSE_HEADER = ['License ID', 'Customer', 'Shop', 'Phone', 'Devices allowed', 'Issued', 'Created', 'Notes'];
var DEVICE_HEADER = ['License ID', 'Device code', 'License key', 'Issued at'];

function doGet() {
  return HtmlService.createTemplateFromFile('Index').evaluate()
    .setTitle('EggVyapar Licenses')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

function include(name) {
  return HtmlService.createHtmlOutputFromFile(name).getContent();
}

/** Run once from the editor after pasting the key into Script Properties: creates the two tabs. */
function setup() {
  sheet_(LICENSES, LICENSE_HEADER);
  sheet_(DEVICES, DEVICE_HEADER);
  return 'Sheets ready. Signing key present: ' + !!signingKeyOrNull_();
}

/** Everything the page needs at start: the signing key (only ever sent to the owner) and the licenses. */
function bootstrap() {
  var key = signingKeyOrNull_();
  if (!key) throw new Error('The signing key is not set. In the script editor: Project Settings > Script properties > SIGNING_KEY_PKCS8.');
  return { signingKey: key, licenses: listLicenses() };
}

function listLicenses() {
  var licenses = rows_(sheet_(LICENSES, LICENSE_HEADER));
  var devices = rows_(sheet_(DEVICES, DEVICE_HEADER));
  return licenses.map(function (l) {
    var own = devices.filter(function (d) { return d[0] === l[0]; });
    return {
      id: l[0], customer: l[1], shop: l[2], phone: String(l[3] || ''), maxDevices: Number(l[4]), issued: l[5] === 'Yes',
      created: String(l[6]), notes: l[7] || '',
      devices: own.map(function (d) { return { deviceCode: d[1], key: d[2], issuedAt: String(d[3]) }; }),
    };
  }).reverse();
}

/**
 * Checks a request before the browser signs: a new license (customer, shop, devices allowed, a random id
 * from the browser) or another phone for an existing license. Returns what to sign, or the existing key
 * when this phone already has one. Refuses a new phone beyond the license's limit.
 */
function prepare(req) {
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var code = normaliseCode_(req.deviceCode);
    var all = listLicenses();
    var license;
    if (req.licenseId) {
      license = all.filter(function (l) { return l.id === req.licenseId; })[0];
      if (!license) throw new Error('No license ' + req.licenseId + ' in the Sheet.');
    } else {
      if (!String(req.customer || '').trim()) throw new Error('Customer name is required.');
      if (!String(req.shop || '').trim()) throw new Error('Shop name is required.');
      var max = Number(req.maxDevices || 1);
      if (!(max >= 1 && max <= 255)) throw new Error('Devices allowed must be between 1 and 255.');
      if (all.some(function (l) { return l.id === req.newLicenseId; })) throw new Error('Please try again (id clash).');
      license = { id: req.newLicenseId, customer: req.customer.trim(), shop: req.shop.trim(), phone: String(req.phone || '').trim(),
        maxDevices: max, issued: false, created: new Date().toISOString(), notes: String(req.notes || '').trim(), devices: [] };
      sheet_(LICENSES, LICENSE_HEADER).appendRow([license.id, license.customer, license.shop, "'" + license.phone, license.maxDevices,
        'No', license.created, license.notes]);
    }
    var existing = license.devices.filter(function (d) { return d.deviceCode === code; })[0];
    if (existing) return { licenseId: license.id, deviceCode: code, existingKey: existing.key, license: license };
    if (license.devices.length >= license.maxDevices) {
      throw new Error(license.id + ' (' + license.customer + ') already has ' + license.devices.length + ' of ' + license.maxDevices +
        ' device(s). Raise "Devices allowed" first.');
    }
    return { licenseId: license.id, deviceCode: code, maxDevices: license.maxDevices, license: license };
  } finally {
    lock.releaseLock();
  }
}

/** Records the key the browser signed and marks the license as issued. */
function saveKey(licenseId, deviceCode, key) {
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    if (!/^EV1-[0-9A-Z-]+$/.test(key)) throw new Error('Not a license key.');
    var devices = sheet_(DEVICES, DEVICE_HEADER);
    var already = rows_(devices).some(function (d) { return d[0] === licenseId && d[1] === deviceCode; });
    if (!already) devices.appendRow([licenseId, deviceCode, key, new Date().toISOString()]);
    var licenses = sheet_(LICENSES, LICENSE_HEADER);
    var data = licenses.getDataRange().getValues();
    for (var i = 1; i < data.length; i++) {
      if (data[i][0] === licenseId) licenses.getRange(i + 1, 6).setValue('Yes');
    }
    return listLicenses();
  } finally {
    lock.releaseLock();
  }
}

/** Changes how many phones a license allows from now on (never below the phones already issued). */
function setLimit(licenseId, max) {
  max = Number(max);
  var license = listLicenses().filter(function (l) { return l.id === licenseId; })[0];
  if (!license) throw new Error('No license ' + licenseId + '.');
  if (!(max >= Math.max(1, license.devices.length) && max <= 255)) {
    throw new Error('Devices allowed must be at least ' + Math.max(1, license.devices.length) + ' and at most 255.');
  }
  var sheet = sheet_(LICENSES, LICENSE_HEADER);
  var data = sheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    if (data[i][0] === licenseId) sheet.getRange(i + 1, 5).setValue(max);
  }
  return listLicenses();
}

// ---- helpers ----

function signingKeyOrNull_() {
  return PropertiesService.getScriptProperties().getProperty('SIGNING_KEY_PKCS8');
}

function sheet_(name, header) {
  var book = SpreadsheetApp.getActive();
  var sheet = book.getSheetByName(name);
  if (!sheet) {
    sheet = book.insertSheet(name);
    sheet.appendRow(header);
    sheet.setFrozenRows(1);
    sheet.getRange(1, 1, 1, header.length).setFontWeight('bold');
  }
  return sheet;
}

function rows_(sheet) {
  var values = sheet.getDataRange().getValues();
  return values.slice(1).filter(function (r) { return r[0]; });
}

/** "zvdx fbjs-7hjt zpdf" → "ZVDX-FBJS-7HJT-ZPDF" (the browser checks it is a real code before this). */
function normaliseCode_(code) {
  var c = String(code || '').toUpperCase().replace(/[^0-9A-Z]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
  if (c.length !== 16) throw new Error('A Device code has 16 characters, like 7K2Q-M9XP-4RTD-H8WN.');
  return c.match(/.{4}/g).join('-');
}
