# SMEDIT CLI Examples

These build configs are patch-only examples. They do not require a ROM.

When using Gradle's `:cli:runCli` task, paths are resolved from the `cli/` module directory, so repository-root paths use `../`.

```bash
./gradlew -q :cli:runCli -Pargs='build --config ../examples/cli/qol-fast-start.json --patch ../build/cli-examples/qol-fast-start.ips --report ../build/cli-examples/qol-fast-start-report.json'
./gradlew -q :cli:runCli -Pargs='build --config ../examples/cli/combat-sandbox.json --patch ../build/cli-examples/combat-sandbox.ips --report ../build/cli-examples/combat-sandbox-report.json'
./gradlew -q :cli:runCli -Pargs='build --config ../examples/cli/boss-lab.json --patch ../build/cli-examples/boss-lab.ips --report ../build/cli-examples/boss-lab-report.json'
```

To emit a patched ROM, add `--rom` and `--output`:

```bash
./gradlew -q :cli:runCli -Pargs='--rom ../path/to/base.smc build --config ../examples/cli/qol-fast-start.json --output ../build/cli-examples/qol-fast-start.smc --patch ../build/cli-examples/qol-fast-start.ips --report ../build/cli-examples/qol-fast-start-report.json'
```

Use `schema <configType>` to inspect valid fields before changing these examples.
