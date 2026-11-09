"""
Drives the real, signed release APK on a device or emulator through Android's own UI tools
(uiautomator), the way a person would tap through it. Used for release validation (docs/RELEASE.md):

  smoke   fresh install: create an account, add a customer, sell with money received now,
          check the balance and the version on About
  seed    same as smoke, keeping the data for the upgrade check
  verify  after `adb install -r` of a newer version: sign in and find the data unchanged

Usage: python scripts/release_check.py <smoke|seed|verify> --serial emulator-5554 [--version 1.0.1 --build 10001]
Exits non-zero with the screen's texts when a step fails. English UI is assumed (the device language).
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.eggbusinessmanager.app'
ADB = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk', 'platform-tools', 'adb.exe')
MOBILE = '9988776655'
PASSWORD = 'eggs2024'
CUSTOMER = 'Upgrade Check Traders'


class Device:
    def __init__(self, serial):
        self.serial = serial
        size = self.shell('wm', 'size')
        w, h = re.findall(r'(\d+)x(\d+)', size)[-1]
        self.width, self.height = int(w), int(h)

    def adb(self, *args, check=True):
        out = subprocess.run([ADB, '-s', self.serial, *args], capture_output=True, text=True, encoding='utf-8')
        if check and out.returncode != 0:
            raise RuntimeError(f'adb {" ".join(args)} failed: {out.stderr.strip()}')
        return out.stdout

    def shell(self, *args):
        return self.adb('shell', *args)

    # ---- Screen ----

    def nodes(self):
        for _ in range(3):
            self.shell('uiautomator', 'dump', '/sdcard/ebm_ui.xml')
            raw = self.adb('exec-out', 'cat', '/sdcard/ebm_ui.xml')
            if raw.strip().startswith('<?xml'):
                return list(ET.fromstring(raw[raw.index('<hierarchy'):]).iter('node'))
            time.sleep(1)
        return []

    @staticmethod
    def label(node):
        return node.get('text') or node.get('content-desc') or node.get('hint') or ''

    def texts(self):
        return [t for t in (self.label(n) for n in self.nodes()) if t]

    def find(self, text, contains=False):
        for node in self.nodes():
            for value in (node.get('text'), node.get('content-desc'), node.get('hint')):
                if value and (text in value if contains else value == text):
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    return (x1 + x2) // 2, (y1 + y2) // 2
        return None

    def wait(self, text, timeout=30, contains=False):
        end = time.time() + timeout
        while time.time() < end:
            if self.find(text, contains):
                return
            time.sleep(1)
        fail(f'"{text}" did not appear', self)

    def scroll_to(self, text, contains=False):
        for _ in range(6):
            point = self.find(text, contains)
            if point and point[1] < self.height * 0.85:
                return point
            self.shell('input', 'swipe', str(self.width // 2), str(int(self.height * 0.75)),
                       str(self.width // 2), str(int(self.height * 0.35)), '400')
            time.sleep(0.8)
        point = self.find(text, contains)
        if not point:
            fail(f'"{text}" not found', self)
        return point

    def tap(self, text, contains=False):
        x, y = self.scroll_to(text, contains)
        self.shell('input', 'tap', str(x), str(y))
        time.sleep(0.8)

    def type(self, label, value):
        self.tap(label)
        self.shell('input', 'text', value.replace(' ', '%s'))
        self.hide_keyboard()

    def hide_keyboard(self):
        """Closes the on-screen keyboard (Back) only when it is showing, so it covers nothing."""
        time.sleep(0.5)
        if 'mInputShown=true' in self.shell('dumpsys', 'input_method'):
            self.shell('input', 'keyevent', '4')
            time.sleep(0.6)

    def tap_fab(self):
        """The floating "Add customer" button: its label is drawn but uiautomator reports it blank, so tap it by place."""
        for node in self.nodes():
            if node.get('class') == 'android.widget.Button' and not self.label(node):
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                if x1 > self.width * 0.4 and y2 < self.height * 0.92:
                    self.shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                    time.sleep(0.8)
                    return
        fail('the Add customer button was not found', self)

    def choose(self, label, option):
        self.tap(label)
        self.wait(option, 10)
        self.tap(option)

    def launch(self):
        self.shell('am', 'start', '-W', '-n', f'{PACKAGE}/.MainActivity')

    def crashes(self):
        return self.adb('logcat', '-d', '-b', 'crash', check=False)


def fail(message, device):
    print('FAILED:', message)
    print('On screen:', ' | '.join(device.texts())[:1500])
    crash = device.crashes()
    if PACKAGE in crash:
        print('Crash log:\n', crash[-3000:])
    sys.exit(1)


def activate_if_needed(d):
    """The release app asks for a lifetime license first: read the Device code, make a key with the
    owner's license tool (using a scratch registry, so the real one stays clean) and paste it."""
    end = time.time() + 40
    while time.time() < end:
        texts = d.texts()
        if 'Activate EggVyapar' in texts:
            break
        if 'Create account' in texts or 'Welcome back' in texts:
            return
        time.sleep(1)
    else:
        return
    code = next(t for t in d.texts() if re.fullmatch(r'[0-9A-Z]{4}(-[0-9A-Z]{4}){3}', t))
    key = issue_check_key(code)
    d.tap('License key (starts with EV1-)')
    # Clear anything typed before, then type the key in short pieces: adb drops characters on long text.
    for _ in range(3):
        d.shell('input', 'keyevent', 'KEYCODE_MOVE_END')
        d.shell('input', 'keyevent', *(['KEYCODE_DEL'] * 80))
    for i in range(0, len(key), 20):
        d.shell('input', 'text', key[i:i + 20])
    d.hide_keyboard()
    d.tap('Activate')
    print('activated with a check key for device', code)


def issue_check_key(code):
    import shutil
    import tempfile
    real = os.path.join(os.environ['USERPROFILE'], '.eggvyapar-license')
    tool = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'license.bat')  # runs it with Java 17
    scratch = tempfile.mkdtemp(prefix='ebm-check-')
    try:
        for name in ('signing-key.pk8', 'public-key.txt'):
            shutil.copy(os.path.join(real, name), scratch)
        out = subprocess.run([tool, 'sell', '--customer', 'Release check', '--shop', 'Emulator', '--device', code,
                              '--home', scratch], capture_output=True, text=True, check=True).stdout
        return [line for line in out.splitlines() if line.startswith('EV1-')][-1]
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


def seed(d, version, build):
    d.adb('logcat', '-c', '-b', 'crash', check=False)
    d.launch()
    activate_if_needed(d)
    d.wait('Create account', 60)
    d.tap('Create account')
    d.wait('Your details')
    d.type('Owner name', 'Ramesh Patil')
    d.type('Mobile number', MOBILE)
    d.type('Password', PASSWORD)
    d.type('Confirm password', PASSWORD)
    d.tap('Next')
    d.wait('Shop details')
    d.type('Shop name', 'Patil Egg Centre')
    d.type('City', 'Nashik')
    d.choose('State or union territory', 'Andhra Pradesh')
    d.type('PIN code', '422001')
    d.tap('Next')
    d.wait('Password recovery')
    d.type('Recovery PIN (6 digits)', '482916')
    d.type('Confirm recovery PIN', '482916')
    d.choose('Question 1', 'What was the name of your first school?')
    d.type('Answer 1', 'Saraswati')
    d.choose('Question 2', 'In which town or village were you born?')
    d.type('Answer 2', 'Nashik')
    d.choose('Question 3', 'What is your favourite dish?')
    d.type('Answer 3', 'Poha')
    d.tap('Create account')
    d.wait('Dashboard', 90)

    d.tap('Customers')
    d.wait('No customers yet')
    d.tap_fab()
    d.wait('Customer name')
    d.type('Customer name', CUSTOMER)
    d.type('Amount (₹)', '1000')
    d.tap('Save')
    d.wait('Sell eggs', 30)
    d.tap('Sell eggs')
    d.wait('Quantity')
    d.choose('Unit', 'Tray (30 eggs)')
    d.type('Quantity', '10')
    d.type('Price per Tray (₹)', '180')
    d.wait('₹1,800.00')
    d.type('Amount received now (optional)', '500')
    d.tap('Save')
    d.wait('Saved S-000001', 30)
    d.tap('Done')
    d.wait('To receive ₹2,300.00', 30)
    about(d, version, build)


def verify(d, version, build):
    d.adb('logcat', '-c', '-b', 'crash', check=False)
    d.launch()
    activate_if_needed(d)  # an update from a version without licensing asks once
    # The account survived the update: the app asks to sign in, not to create an account.
    d.wait('Welcome back', 60)
    d.type('Mobile number or email', MOBILE)
    d.type('Password', PASSWORD)
    d.tap('Sign in')
    d.wait('Dashboard', 90)
    d.tap('Customers')
    d.wait(CUSTOMER, 30)
    d.tap(CUSTOMER)
    d.wait('To receive ₹2,300.00', 30)
    d.tap('Transaction history')
    d.wait('S-000001', 30, contains=True)
    d.wait('RP-000001', 30, contains=True)
    d.shell('input', 'keyevent', '4')
    d.shell('input', 'keyevent', '4')
    about(d, version, build)


def about(d, version, build):
    d.tap('More')
    d.tap('About')
    d.wait(f'Version {version} (build {build})', 30)
    if PACKAGE in d.crashes():
        fail('the app crashed during the check', d)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('step', choices=['smoke', 'seed', 'verify'])
    parser.add_argument('--serial', required=True)
    parser.add_argument('--version', default='1.0.0')
    parser.add_argument('--build', default='10000')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    d = Device(args.serial)
    if args.step == 'verify':
        verify(d, args.version, args.build)
    else:
        seed(d, args.version, args.build)
    print(f'OK: {args.step} passed on {args.serial}')


if __name__ == '__main__':
    main()
