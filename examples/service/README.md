# SMEDIT Service Examples

Run the service first:

```bash
./gradlew :smedit-service:runService
```

Then call the API with a local ROM file:

```bash
examples/service/random-spicy-combat.sh path/to/base.smc build/random-spicy-combat.smc
examples/service/random-spicy-combat.sh --colorize psychedelic path/to/base.smc build/random-spicy-combat.smc
```

`random-spicy-combat.sh` applies `skip_intro_and_ceres`, quick `fanfares`, and the `spicy` combat randomizer with Metroids excluded and basic beams included. `--colorize <effect>` adds a build-level palette effect such as `psychedelic`.
