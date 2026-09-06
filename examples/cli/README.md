# SMEDIT CLI Examples

These build configs are patch-only examples. They do not require a ROM.

When using Gradle's `:cli:runCli` task, paths are resolved from the `cli/` module directory, so repository-root paths use `../`.

```bash
./gradlew -q :cli:runCli -Pargs='build --config ../examples/cli/qol-fast-start.json --patch ../build/cli-examples/qol-fast-start.ips --report ../build/cli-examples/qol-fast-start-report.json'
./gradlew -q :cli:runCli -Pargs='build --config ../examples/cli/combat-sandbox.json --patch ../build/cli-examples/combat-sandbox.ips --report ../build/cli-examples/combat-sandbox-report.json'
./gradlew -q :cli:runCli -Pargs='build --config ../examples/cli/boss-lab.json --patch ../build/cli-examples/boss-lab.ips --report ../build/cli-examples/boss-lab-report.json'
./gradlew -q :cli:runCli -Pargs='build --config ../examples/cli/echolocation-beam.json --patch ../build/cli-examples/echolocation-beam.ips --report ../build/cli-examples/echolocation-beam-report.json'
./gradlew -q :cli:runCli -Pargs='build --config ../examples/cli/gameplay-timing.json --patch ../build/cli-examples/gameplay-timing.ips --report ../build/cli-examples/gameplay-timing-report.json'
```

To emit a patched ROM, add `--rom` and `--output`:

```bash
./gradlew -q :cli:runCli -Pargs='--rom ../path/to/base.smc build --config ../examples/cli/qol-fast-start.json --output ../build/cli-examples/qol-fast-start.smc --patch ../build/cli-examples/qol-fast-start.ips --report ../build/cli-examples/qol-fast-start-report.json'
```

The headless build engine accepts normal 3 MB ROMs and 3 MB ROMs with a 512-byte SMC header. Headered ROM outputs keep the header, while `--patch` IPS output uses standard headerless offsets.

Use `schema <configType>` to inspect valid fields before changing these examples.
Use `patches` to list public patch IDs. Internally prefixed IDs such as `bundled_*` and `hex_*` are still accepted as aliases, but examples use the cleaner public names.

`gameplay-timing.json` demonstrates the end-game Zebes escape countdown and
Short Charge stage count. The vanilla defaults are 180 seconds and 4 stages;
Short Charge accepts 0–4, where 0 grants blue speed as soon as Dash running
begins.
