package com.eggvyapar.licensetool

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """EggVyapar license tool — lifetime, device-bound license keys (private: keep on your PC).

  license-tool init
      Create your signing key (once). Prints the public key that goes into the app.
  license-tool new --customer "Name" --shop "Shop" [--max-devices N] [--phone P] [--notes T]
      Register a sold license (default 1 device). Prints its license id, e.g. L-7F3KQ2MZ.
  license-tool issue --license L-XXXXXXXX --device XXXX-XXXX-XXXX-XXXX
      Make the key for one phone (refused above the license's device limit). Send the key to the customer.
  license-tool sell --customer "Name" --shop "Shop" --device XXXX-XXXX-XXXX-XXXX [--max-devices N] [--phone P]
      new + issue in one step.
  license-tool set-limit L-XXXXXXXX N      Change a license's device limit.
  license-tool list                        All licenses.
  license-tool show L-XXXXXXXX             One license with its devices and keys.
  license-tool export [--file licenses.csv]  Everything as CSV (Excel).
  license-tool public-key                  Print the public key again.

Options: --home DIR (default: %USERPROFILE%\.eggvyapar-license, or EGGVYAPAR_LICENSE_HOME)"""

fun main(args: Array<String>) {
    val exit = try {
        run(args.toList(), System.out::println)
        0
    } catch (e: LicenseToolException) {
        System.err.println("Error: ${e.message}")
        1
    }
    exitProcess(exit)
}

/** The command line, separated from [main] so tests can drive it. */
fun run(args: List<String>, out: (String) -> Unit) {
    val options = Options.parse(args)
    val home = LicenseHome(File(options["home"] ?: System.getenv("EGGVYAPAR_LICENSE_HOME") ?: defaultHome()))
    val command = COMMANDS[options.command ?: "help"]
        ?: throw LicenseToolException("Unknown command \"${options.command}\".\n\n$USAGE")
    command(Tool(home, LicenseRegistry(home), options, out))
}

private class Tool(val home: LicenseHome, val registry: LicenseRegistry, val options: Options, val out: (String) -> Unit) {
    fun newLicense(): LicenseRecord = registry.create(
        customer = options.need("customer"),
        shop = options.need("shop"),
        maxDevices = options.int("max-devices", 1),
        phone = options["phone"].orEmpty(),
        notes = options["notes"].orEmpty(),
    )
}

private val COMMANDS: Map<String, (Tool) -> Unit> = mapOf(
    "init" to { t ->
        val public = t.home.init()
        t.out("Signing key created in ${t.home.dir}")
        t.out("BACK UP this folder now (see docs/LICENSING.md). Without it no new keys can be made.")
        t.out("Public key (goes into the app):")
        t.out(public)
    },
    "public-key" to { t -> t.out(t.home.publicKey()) },
    "new" to { t -> t.out(describe(t.newLicense())) },
    "issue" to { t -> printKey(t.registry.issue(t.options.need("license"), t.options.need("device")), t.out) },
    "sell" to { t ->
        val record = t.newLicense()
        t.out(describe(record))
        printKey(t.registry.issue(record.id, t.options.need("device")), t.out)
    },
    "set-limit" to { t ->
        val limit = t.options.positional(1).toIntOrNull() ?: throw LicenseToolException("The limit must be a number.")
        t.out(describe(t.registry.setLimit(t.options.positional(0), limit)))
    },
    "list" to { t ->
        val all = t.registry.list()
        if (all.isEmpty()) t.out("No licenses yet.") else all.forEach { t.out(describe(it)) }
    },
    "show" to { t ->
        val record = t.registry.get(t.options.positional(0))
        t.out(describe(record))
        record.devices.forEach { t.out("  ${it.deviceCode}  issued ${it.issuedAt}\n  ${it.key}") }
    },
    "export" to { t ->
        val csv = t.registry.exportCsv()
        val file = t.options["file"]?.let(::File)
        if (file == null) {
            t.out(csv.trimEnd())
        } else {
            file.writeText("﻿" + csv) // BOM so Excel reads names in any language
            t.out("Exported to ${file.absolutePath}")
        }
    },
    "help" to { t -> t.out(USAGE) },
    "--help" to { t -> t.out(USAGE) },
)

private fun describe(r: LicenseRecord) =
    "${r.id}  ${r.customer} · ${r.shop}${if (r.phone.isNotBlank()) " · ${r.phone}" else ""}  " +
        "devices ${r.devices.size}/${r.maxDevices}  ${if (r.issued) "issued" else "not issued"}  created ${r.createdAt}"

private fun printKey(device: DeviceRecord, out: (String) -> Unit) {
    out("License key for device ${device.deviceCode} — send this to the customer:")
    out(device.key)
}

private fun defaultHome(): String =
    File(System.getenv("USERPROFILE") ?: System.getProperty("user.home"), ".eggvyapar-license").path

/** `command`, `--name value` pairs and positional arguments. */
private class Options(val command: String?, private val named: Map<String, String>, private val positionals: List<String>) {
    operator fun get(name: String): String? = named[name]

    fun need(name: String): String = named[name]?.takeIf { it.isNotBlank() } ?: throw LicenseToolException("Missing --$name")

    fun int(name: String, default: Int): Int =
        named[name]?.let { it.toIntOrNull() ?: throw LicenseToolException("--$name must be a number") } ?: default

    fun positional(index: Int): String = positionals.getOrNull(index) ?: throw LicenseToolException("Missing argument ${index + 1}")

    companion object {
        fun parse(args: List<String>): Options {
            val named = mutableMapOf<String, String>()
            val positionals = mutableListOf<String>()
            var i = 1
            while (i < args.size) {
                val arg = args[i]
                if (arg.startsWith("--")) {
                    named[arg.removePrefix("--")] = args.getOrNull(i + 1) ?: throw LicenseToolException("Missing value for $arg")
                    i += 2
                } else {
                    positionals += arg
                    i++
                }
            }
            return Options(args.firstOrNull(), named, positionals)
        }
    }
}
