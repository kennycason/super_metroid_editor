import { ChangeEvent, DragEvent, ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import {
  Check,
  Download,
  FileArchive,
  History,
  Loader2,
  Play,
  Trash2,
  Upload,
} from 'lucide-react';
import { base64ToBlob, downloadBlob, fetchMetadata, patchIps, patchRom, stripEmpty } from './api';
import { deleteRom, getRom, listRoms, saveRomFile } from './storage';
import type {
  BuildRequest,
  PatchId,
  PatchOption,
  RandomizationRequest,
  RomHistorySummary,
  ServiceMetadata,
  ServicePatchResponse,
} from './types';

const serviceUrlDefault = import.meta.env.VITE_SMEDIT_SERVICE_URL || 'http://localhost:8080';

const patchOptions: PatchOption[] = [
  { id: 'skip_intro_and_ceres', label: 'Skip Ceres + intro', section: 'Start', defaultEnabled: true },
  { id: 'skip_intro', label: 'Skip intro only', section: 'Start' },
  { id: 'vanilla_bugfixes', label: 'Vanilla bugfixes', section: 'Start' },
  { id: 'fanfares', label: 'Quick item fanfares', section: 'Start', defaultEnabled: true },
  { id: 'bombs', label: 'Bomb tuning', section: 'Combat' },
  { id: 'higher_jump', label: 'Higher jump', section: 'Movement' },
  { id: 'energy_free_shinesparks', label: 'Energy-free shinesparks', section: 'Movement' },
  { id: 'enable_moonwalk', label: 'Enable moonwalk', section: 'Movement' },
  { id: 'fast_doors', label: 'Fast doors', section: 'Movement' },
  { id: 'fast_elevators', label: 'Fast elevators', section: 'Movement' },
  { id: 'infinite_missiles', label: 'Infinite missiles', section: 'Supplies' },
  { id: 'infinite_super_missiles', label: 'Infinite supers', section: 'Supplies' },
  { id: 'infinite_power_bombs', label: 'Infinite power bombs', section: 'Supplies' },
  { id: 'hyper_beam', label: 'Hyper beam', section: 'Combat' },
  { id: 'infinite_blue_suit', label: 'Infinite blue suit', section: 'Combat' },
];

const fallbackColorEffects = [
  { id: 'psychedelic', name: 'Psychedelic' },
  { id: 'vaporwave', name: 'Vaporwave' },
  { id: 'acid', name: 'Acid Trip' },
  { id: 'cyberpunk', name: 'Cyberpunk' },
  { id: 'rainbow', name: 'Rainbow' },
  { id: 'thermal', name: 'Thermal' },
  { id: 'hologram', name: 'Hologram' },
  { id: 'pastel', name: 'Pastel' },
  { id: 'golden', name: 'Golden' },
  { id: 'icecold', name: 'Ice Cold' },
  { id: 'lava', name: 'Lava' },
  { id: 'underwater', name: 'Underwater' },
  { id: 'grayscale', name: 'Grayscale' },
  { id: 'invert', name: 'Invert' },
];

const fallbackBeamOptions = ['power', 'ice', 'spazer', 'wave', 'plasma', 'is', 'iw', 'ws', 'iws', 'ip', 'wp', 'iwp'];
const fallbackCategoryOptions = ['Aquatic', 'Crawler', 'Flyer', 'Hopper', 'Pirate', 'Spawner', 'Special'];
const defaultPatches = Object.fromEntries(patchOptions.map((option) => [option.id, !!option.defaultEnabled])) as Record<
  PatchId,
  boolean
>;

type OutputMode = 'rom' | 'ips';

type BombSettings = {
  maxActiveBombs: number;
  fuseFrames: number;
  cooldownFrames: number;
  explosionDelay: number;
};

type PersistedSettings = {
  serviceUrl?: string;
  selectedRomId?: string;
  enabledPatches?: Partial<Record<PatchId, boolean>>;
  fanfareFrames?: number;
  outputMode?: OutputMode;
  bombs?: Partial<BombSettings>;
  colorize?: {
    enabled?: boolean;
    effect?: string;
    includeTilesets?: boolean;
    includeSprites?: boolean;
    tilesetFilter?: string;
    spriteRegionFilter?: string;
  };
  randomizer?: {
    enabled?: boolean;
    preset?: string;
    seed?: string;
    selectedBeams?: string[];
    excludeMetroid?: boolean;
    extraExcludedEnemies?: string;
    includeCategories?: string[];
    excludeCategories?: string[];
    overrideBeamDamage?: boolean;
    beamDamageMin?: number;
    beamDamageMax?: number;
    overrideEnemyStats?: boolean;
    enemyHpMin?: number;
    enemyHpMax?: number;
    enemyDamageMin?: number;
    enemyDamageMax?: number;
    randomizeHp?: boolean;
    randomizeContactDamage?: boolean;
    overrideDrops?: boolean;
    dropNothingWeight?: number;
    dropMinNonZeroSlots?: number;
    dropMaxNothing?: number;
    overrideVulnerabilities?: boolean;
    noEffectChance?: number;
    minEffectiveWeapons?: number;
    multipliers?: string;
    requireMissiles?: boolean;
  };
};

type ResultState = {
  response: ServicePatchResponse;
  romBlob: Blob;
  ipsBlob: Blob;
  baseName: string;
};

const settingsStorageKey = 'smedit-service-app-settings-v1';
const defaultBombSettings: BombSettings = {
  maxActiveBombs: 5,
  fuseFrames: 10,
  cooldownFrames: 1,
  explosionDelay: 1,
};

export function App() {
  const savedSettings = useMemo(loadPersistedSettings, []);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [serviceUrl, setServiceUrl] = useState(savedSettings.serviceUrl ?? serviceUrlDefault);
  const [history, setHistory] = useState<RomHistorySummary[]>([]);
  const [selectedRomId, setSelectedRomId] = useState<string>(savedSettings.selectedRomId ?? '');
  const [metadata, setMetadata] = useState<ServiceMetadata | null>(null);
  const [metadataError, setMetadataError] = useState<string | null>(null);
  const [isDragging, setDragging] = useState(false);
  const [enabledPatches, setEnabledPatches] = useState<Record<PatchId, boolean>>(
    mergePatchSettings(savedSettings.enabledPatches),
  );
  const [fanfareFrames, setFanfareFrames] = useState(numberSetting(savedSettings.fanfareFrames, 16));
  const [outputMode, setOutputMode] = useState<OutputMode>(savedSettings.outputMode === 'ips' ? 'ips' : 'rom');
  const [bombMaxActive, setBombMaxActive] = useState(
    numberSetting(savedSettings.bombs?.maxActiveBombs, defaultBombSettings.maxActiveBombs),
  );
  const [bombFuseFrames, setBombFuseFrames] = useState(
    numberSetting(savedSettings.bombs?.fuseFrames, defaultBombSettings.fuseFrames),
  );
  const [bombCooldownFrames, setBombCooldownFrames] = useState(
    numberSetting(savedSettings.bombs?.cooldownFrames, defaultBombSettings.cooldownFrames),
  );
  const [bombExplosionDelay, setBombExplosionDelay] = useState(
    numberSetting(savedSettings.bombs?.explosionDelay, defaultBombSettings.explosionDelay),
  );

  const [colorizeEnabled, setColorizeEnabled] = useState(booleanSetting(savedSettings.colorize?.enabled, true));
  const [colorEffect, setColorEffect] = useState(savedSettings.colorize?.effect ?? 'psychedelic');
  const [includeTilesets, setIncludeTilesets] = useState(booleanSetting(savedSettings.colorize?.includeTilesets, true));
  const [includeSprites, setIncludeSprites] = useState(booleanSetting(savedSettings.colorize?.includeSprites, true));
  const [tilesetFilter, setTilesetFilter] = useState(savedSettings.colorize?.tilesetFilter ?? '');
  const [spriteRegionFilter, setSpriteRegionFilter] = useState(savedSettings.colorize?.spriteRegionFilter ?? '');

  const [randomEnabled, setRandomEnabled] = useState(booleanSetting(savedSettings.randomizer?.enabled, true));
  const [preset, setPreset] = useState(savedSettings.randomizer?.preset ?? 'spicy');
  const [seed, setSeed] = useState(savedSettings.randomizer?.seed ?? '');
  const [selectedBeams, setSelectedBeams] = useState<string[]>(
    stringArraySetting(savedSettings.randomizer?.selectedBeams, ['power', 'ice', 'wave', 'plasma']),
  );
  const [excludeMetroid, setExcludeMetroid] = useState(booleanSetting(savedSettings.randomizer?.excludeMetroid, true));
  const [extraExcludedEnemies, setExtraExcludedEnemies] = useState(savedSettings.randomizer?.extraExcludedEnemies ?? '');
  const [includeCategories, setIncludeCategories] = useState<string[]>(
    stringArraySetting(savedSettings.randomizer?.includeCategories, []),
  );
  const [excludeCategories, setExcludeCategories] = useState<string[]>(
    stringArraySetting(savedSettings.randomizer?.excludeCategories, ['Special']),
  );

  const [overrideBeamDamage, setOverrideBeamDamage] = useState(booleanSetting(savedSettings.randomizer?.overrideBeamDamage, false));
  const [beamDamageMin, setBeamDamageMin] = useState(numberSetting(savedSettings.randomizer?.beamDamageMin, 0.5));
  const [beamDamageMax, setBeamDamageMax] = useState(numberSetting(savedSettings.randomizer?.beamDamageMax, 2.75));
  const [overrideEnemyStats, setOverrideEnemyStats] = useState(booleanSetting(savedSettings.randomizer?.overrideEnemyStats, false));
  const [enemyHpMin, setEnemyHpMin] = useState(numberSetting(savedSettings.randomizer?.enemyHpMin, 0.5));
  const [enemyHpMax, setEnemyHpMax] = useState(numberSetting(savedSettings.randomizer?.enemyHpMax, 3.5));
  const [enemyDamageMin, setEnemyDamageMin] = useState(numberSetting(savedSettings.randomizer?.enemyDamageMin, 0.5));
  const [enemyDamageMax, setEnemyDamageMax] = useState(numberSetting(savedSettings.randomizer?.enemyDamageMax, 2.25));
  const [randomizeHp, setRandomizeHp] = useState(booleanSetting(savedSettings.randomizer?.randomizeHp, true));
  const [randomizeContactDamage, setRandomizeContactDamage] = useState(
    booleanSetting(savedSettings.randomizer?.randomizeContactDamage, true),
  );
  const [overrideDrops, setOverrideDrops] = useState(booleanSetting(savedSettings.randomizer?.overrideDrops, false));
  const [dropNothingWeight, setDropNothingWeight] = useState(numberSetting(savedSettings.randomizer?.dropNothingWeight, 5));
  const [dropMinNonZeroSlots, setDropMinNonZeroSlots] = useState(
    numberSetting(savedSettings.randomizer?.dropMinNonZeroSlots, 2),
  );
  const [dropMaxNothing, setDropMaxNothing] = useState(numberSetting(savedSettings.randomizer?.dropMaxNothing, 220));
  const [overrideVulnerabilities, setOverrideVulnerabilities] = useState(
    booleanSetting(savedSettings.randomizer?.overrideVulnerabilities, false),
  );
  const [noEffectChance, setNoEffectChance] = useState(numberSetting(savedSettings.randomizer?.noEffectChance, 0.25));
  const [minEffectiveWeapons, setMinEffectiveWeapons] = useState(numberSetting(savedSettings.randomizer?.minEffectiveWeapons, 2));
  const [multipliers, setMultipliers] = useState(savedSettings.randomizer?.multipliers ?? '1,2,4,8');
  const [requireMissiles, setRequireMissiles] = useState(booleanSetting(savedSettings.randomizer?.requireMissiles, true));

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [result, setResult] = useState<ResultState | null>(null);

  useEffect(() => {
    void refreshHistory();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    async function loadMetadata() {
      try {
        const loaded = await fetchMetadata(serviceUrl);
        if (controller.signal.aborted) return;
        setMetadata(loaded);
        setMetadataError(null);
      } catch (err) {
        if (controller.signal.aborted) return;
        setMetadata(null);
        setMetadataError(`Metadata unavailable: ${err instanceof Error ? err.message : String(err)}`);
      }
    }

    void loadMetadata();
    return () => controller.abort();
  }, [serviceUrl]);

  useEffect(() => {
    savePersistedSettings({
      serviceUrl,
      selectedRomId,
      enabledPatches,
      fanfareFrames,
      outputMode,
      bombs: {
        maxActiveBombs: bombMaxActive,
        fuseFrames: bombFuseFrames,
        cooldownFrames: bombCooldownFrames,
        explosionDelay: bombExplosionDelay,
      },
      colorize: {
        enabled: colorizeEnabled,
        effect: colorEffect,
        includeTilesets,
        includeSprites,
        tilesetFilter,
        spriteRegionFilter,
      },
      randomizer: {
        enabled: randomEnabled,
        preset,
        seed,
        selectedBeams,
        excludeMetroid,
        extraExcludedEnemies,
        includeCategories,
        excludeCategories,
        overrideBeamDamage,
        beamDamageMin,
        beamDamageMax,
        overrideEnemyStats,
        enemyHpMin,
        enemyHpMax,
        enemyDamageMin,
        enemyDamageMax,
        randomizeHp,
        randomizeContactDamage,
        overrideDrops,
        dropNothingWeight,
        dropMinNonZeroSlots,
        dropMaxNothing,
        overrideVulnerabilities,
        noEffectChance,
        minEffectiveWeapons,
        multipliers,
        requireMissiles,
      },
    });
  }, [
    beamDamageMax,
    beamDamageMin,
    bombCooldownFrames,
    bombExplosionDelay,
    bombFuseFrames,
    bombMaxActive,
    colorEffect,
    colorizeEnabled,
    dropMaxNothing,
    dropMinNonZeroSlots,
    dropNothingWeight,
    enabledPatches,
    enemyDamageMax,
    enemyDamageMin,
    enemyHpMax,
    enemyHpMin,
    excludeCategories,
    excludeMetroid,
    extraExcludedEnemies,
    fanfareFrames,
    includeCategories,
    includeSprites,
    includeTilesets,
    minEffectiveWeapons,
    multipliers,
    noEffectChance,
    outputMode,
    overrideBeamDamage,
    overrideDrops,
    overrideEnemyStats,
    overrideVulnerabilities,
    preset,
    randomEnabled,
    randomizeContactDamage,
    randomizeHp,
    requireMissiles,
    seed,
    selectedBeams,
    selectedRomId,
    serviceUrl,
    spriteRegionFilter,
    tilesetFilter,
  ]);

  const selectedRom = history.find((item) => item.id === selectedRomId);
  const availablePatchIds = useMemo(() => new Set(metadata?.patches.map((patch) => patch.id)), [metadata]);
  const visiblePatchOptions = useMemo(
    () => patchOptions.filter((option) => !metadata || availablePatchIds.has(option.id)),
    [availablePatchIds, metadata],
  );
  const colorEffectOptions = metadata?.colorize.effects ?? fallbackColorEffects;
  const beamOptions = metadata?.randomization.beams ?? fallbackBeamOptions;
  const categoryOptions = metadata?.randomization.enemyCategories ?? fallbackCategoryOptions;
  const presetOptions = metadata?.randomization.presets ?? ['balanced', 'spicy', 'chaos', 'survival'];

  const buildRequest = useMemo<BuildRequest>(() => {
    const patches: NonNullable<BuildRequest['patches']> = {};
    for (const option of visiblePatchOptions) {
      if (!enabledPatches[option.id]) continue;
      if (option.id === 'fanfares') {
        patches[option.id] = { enabled: true, config: { item_fanfare_frames: fanfareFrames } };
      } else if (option.id === 'bombs') {
        patches[option.id] = {
          enabled: true,
          config: {
            max_active_bombs: bombMaxActive,
            fuse_frames: bombFuseFrames,
            cooldown_frames: bombCooldownFrames,
            explosion_frame_delay: bombExplosionDelay,
          },
        };
      } else {
        patches[option.id] = { enabled: true };
      }
    }

    const build: BuildRequest = {
      schemaVersion: 1,
      patches,
    };

    if (colorizeEnabled) {
      build.colorize = {
        effect: colorEffect,
        includeTilesets,
        includeSprites,
        tilesets: parseIntegerList(tilesetFilter),
        spriteRegions: parseStringList(spriteRegionFilter),
      };
    }

    return stripEmpty(build);
  }, [
    bombCooldownFrames,
    bombExplosionDelay,
    bombFuseFrames,
    bombMaxActive,
    colorEffect,
    colorizeEnabled,
    enabledPatches,
    fanfareFrames,
    includeSprites,
    includeTilesets,
    spriteRegionFilter,
    tilesetFilter,
    visiblePatchOptions,
  ]);

  const randomizationRequest = useMemo<RandomizationRequest | undefined>(() => {
    if (!randomEnabled) return undefined;

    const excludedEnemies = [...(excludeMetroid ? ['metroid'] : []), ...parseStringList(extraExcludedEnemies)];
    const request: RandomizationRequest = {
      preset,
      seed: parseOptionalInteger(seed),
      includeBeams: selectedBeams,
      excludeEnemies: excludedEnemies,
      includeEnemyCategories: includeCategories,
      excludeEnemyCategories: excludeCategories,
    };

    if (overrideBeamDamage) {
      request.beamDamage = {
        enabled: true,
        damageMin: beamDamageMin,
        damageMax: beamDamageMax,
      };
    }
    if (overrideEnemyStats) {
      request.enemyStats = {
        enabled: true,
        randomizeHp,
        randomizeContactDamage,
        enemyHpMin,
        enemyHpMax,
        enemyDamageMin,
        enemyDamageMax,
        preserveOneHpEnemies: true,
        preserveZeroDamageEnemies: true,
      };
    }
    if (overrideDrops) {
      request.enemyDrops = {
        enabled: true,
        total: 255,
        smallEnergyWeight: 2,
        largeEnergyWeight: 1,
        missileWeight: 2,
        nothingWeight: dropNothingWeight,
        superMissileWeight: 0.6,
        powerBombWeight: 0.4,
        minNonZeroSlots: dropMinNonZeroSlots,
        maxNothing: dropMaxNothing,
      };
    }
    if (overrideVulnerabilities) {
      request.enemyVulnerabilities = {
        enabled: true,
        noEffectChance,
        multipliers: parseIntegerList(multipliers),
        ensureAtLeastOneEffectivePerEnemy: true,
        minEffectiveWeaponsPerEnemy: minEffectiveWeapons,
        requiredEffectiveWeaponSlots: requireMissiles ? [21] : [],
      };
    }

    return stripEmpty(request);
  }, [
    beamDamageMax,
    beamDamageMin,
    dropMaxNothing,
    dropMinNonZeroSlots,
    dropNothingWeight,
    enemyDamageMax,
    enemyDamageMin,
    enemyHpMax,
    enemyHpMin,
    excludeCategories,
    excludeMetroid,
    extraExcludedEnemies,
    includeCategories,
    minEffectiveWeapons,
    multipliers,
    noEffectChance,
    overrideBeamDamage,
    overrideDrops,
    overrideEnemyStats,
    overrideVulnerabilities,
    preset,
    randomEnabled,
    randomizeContactDamage,
    randomizeHp,
    requireMissiles,
    seed,
    selectedBeams,
  ]);

  const validationErrors = useMemo(() => {
    const errors: string[] = [];
    if (
      enabledPatches.fanfares &&
      (!Number.isFinite(fanfareFrames) || !Number.isInteger(fanfareFrames) || fanfareFrames < 1 || fanfareFrames > 9999)
    ) {
      errors.push('Fanfare frames must be an integer from 1 to 9999.');
    }
    if (enabledPatches.bombs) {
      errors.push(...validateIntegerField('Max active bombs', bombMaxActive, 1, 5));
      errors.push(...validateIntegerField('Bomb fuse frames', bombFuseFrames, 1, 9999));
      errors.push(...validateIntegerField('Bomb cooldown frames', bombCooldownFrames, 0, 255));
      errors.push(...validateIntegerField('Bomb explosion delay', bombExplosionDelay, 1, 255));
    }

    if (colorizeEnabled) {
      if (!colorEffectOptions.some((effect) => effect.id === colorEffect)) {
        errors.push(`Color effect '${colorEffect}' is not available from this service.`);
      }
      if (!includeTilesets && !includeSprites) {
        errors.push('Colorize must include area tilesets, sprite palettes, or both.');
      }
      const parsedTilesets = parseIntegerListDetailed(tilesetFilter);
      if (parsedTilesets.invalid.length > 0) {
        errors.push(`Tilesets must be comma-separated integers: ${parsedTilesets.invalid.join(', ')}.`);
      }
      const maxTileset = (metadata?.colorize.tilesetCount ?? 29) - 1;
      const invalidTilesets = parsedTilesets.values.filter((id) => id < 0 || id > maxTileset);
      if (invalidTilesets.length > 0) {
        errors.push(`Tileset IDs must be from 0 to ${maxTileset}: ${invalidTilesets.join(', ')}.`);
      }
      if (metadata && spriteRegionFilter.trim()) {
        const knownRegions = new Set(metadata.colorize.spriteRegions.map((region) => region.id));
        const unknownRegions = parseStringList(spriteRegionFilter).filter((region) => !knownRegions.has(region));
        if (unknownRegions.length > 0) {
          errors.push(`Unknown sprite region(s): ${unknownRegions.join(', ')}.`);
        }
      }
    }

    if (randomEnabled) {
      if (seed.trim() && parseOptionalInteger(seed) === undefined) {
        errors.push('Seed must be an integer.');
      }
      if (!presetOptions.includes(preset)) {
        errors.push(`Preset '${preset}' is not available from this service.`);
      }
      const knownBeams = new Set(beamOptions);
      const unknownBeams = selectedBeams.filter((beam) => !knownBeams.has(beam));
      if (unknownBeams.length > 0) {
        errors.push(`Unknown beam filter(s): ${unknownBeams.join(', ')}.`);
      }
      if (metadata) {
        const knownCategories = new Set(categoryOptions);
        const unknownCategories = [...includeCategories, ...excludeCategories].filter((category) => !knownCategories.has(category));
        if (unknownCategories.length > 0) {
          errors.push(`Unknown enemy category filter(s): ${[...new Set(unknownCategories)].join(', ')}.`);
        }
        const knownEnemies = new Set(metadata.randomization.enemies.map((enemy) => enemy.key));
        const unknownEnemies = parseStringList(extraExcludedEnemies).filter((enemy) => !knownEnemies.has(enemy));
        if (unknownEnemies.length > 0) {
          errors.push(`Unknown excluded enemy key(s): ${unknownEnemies.join(', ')}.`);
        }
      }
      if (overrideBeamDamage) {
        errors.push(...validateOrderedRange('Beam damage rate', beamDamageMin, beamDamageMax, 0));
      }
      if (overrideEnemyStats) {
        if (!randomizeHp && !randomizeContactDamage) {
          errors.push('Enemy stats must randomize HP, contact damage, or both.');
        }
        errors.push(...validateOrderedRange('Enemy HP rate', enemyHpMin, enemyHpMax, 0));
        errors.push(...validateOrderedRange('Enemy damage rate', enemyDamageMin, enemyDamageMax, 0));
      }
      if (overrideDrops) {
        if (!Number.isFinite(dropNothingWeight) || dropNothingWeight < 0) {
          errors.push('Enemy drops nothing weight must be non-negative.');
        }
        if (!Number.isInteger(dropMinNonZeroSlots) || dropMinNonZeroSlots < 1 || dropMinNonZeroSlots > 6) {
          errors.push('Enemy drops min slots must be an integer from 1 to 6.');
        }
        if (!Number.isInteger(dropMaxNothing) || dropMaxNothing < 0 || dropMaxNothing > 255) {
          errors.push('Enemy drops max nothing must be an integer from 0 to 255.');
        }
      }
      if (overrideVulnerabilities) {
        if (!Number.isFinite(noEffectChance) || noEffectChance < 0 || noEffectChance > 1) {
          errors.push('No effect chance must be from 0.0 to 1.0.');
        }
        if (!Number.isInteger(minEffectiveWeapons) || minEffectiveWeapons < 0 || minEffectiveWeapons > 22) {
          errors.push('Min effective weapons must be an integer from 0 to 22.');
        }
        const parsedMultipliers = parseIntegerListDetailed(multipliers);
        if (parsedMultipliers.invalid.length > 0) {
          errors.push(`Multipliers must be comma-separated integers: ${parsedMultipliers.invalid.join(', ')}.`);
        }
        if (parsedMultipliers.values.length === 0) {
          errors.push('Multipliers must include at least one value.');
        }
        const invalidMultipliers = parsedMultipliers.values.filter((value) => value < 1 || value > 255);
        if (invalidMultipliers.length > 0) {
          errors.push(`Multipliers must be from 1 to 255: ${invalidMultipliers.join(', ')}.`);
        }
      }
    }

    return errors;
  }, [
    beamDamageMax,
    beamDamageMin,
    beamOptions,
    bombCooldownFrames,
    bombExplosionDelay,
    bombFuseFrames,
    bombMaxActive,
    categoryOptions,
    colorEffect,
    colorEffectOptions,
    colorizeEnabled,
    dropMaxNothing,
    dropMinNonZeroSlots,
    dropNothingWeight,
    enemyDamageMax,
    enemyDamageMin,
    enemyHpMax,
    enemyHpMin,
    enabledPatches.bombs,
    enabledPatches.fanfares,
    excludeCategories,
    extraExcludedEnemies,
    fanfareFrames,
    includeCategories,
    includeSprites,
    includeTilesets,
    metadata,
    minEffectiveWeapons,
    multipliers,
    noEffectChance,
    overrideBeamDamage,
    overrideDrops,
    overrideEnemyStats,
    overrideVulnerabilities,
    preset,
    presetOptions,
    randomEnabled,
    randomizeContactDamage,
    randomizeHp,
    seed,
    selectedBeams,
    tilesetFilter,
    spriteRegionFilter,
  ]);

  const previewJson = useMemo(
    () =>
      JSON.stringify(
        {
          build: buildRequest,
          randomize: randomizationRequest,
        },
        null,
        2,
      ),
    [buildRequest, randomizationRequest],
  );

  async function refreshHistory(nextSelectedId?: string) {
    try {
      const items = await listRoms();
      setHistory(items);
      setSelectedRomId((current) => {
        if (nextSelectedId !== undefined) return nextSelectedId || items[0]?.id || '';
        if (current && items.some((item) => item.id === current)) return current;
        return items[0]?.id || '';
      });
    } catch (err) {
      setError(`Could not read local ROM history: ${errorText(err)}`);
    }
  }

  async function importFile(file: File) {
    setError(null);
    setNotice(null);
    if (!/\.(smc|sfc)$/i.test(file.name)) {
      setError('Use a .smc or .sfc ROM file.');
      return;
    }
    try {
      const summary = await saveRomFile(file);
      await refreshHistory(summary.id);
      setNotice(`Stored ${summary.name}`);
    } catch (err) {
      setError(`Could not store ROM locally: ${errorText(err)}`);
    }
  }

  async function handleFiles(files: FileList | File[]) {
    const file = Array.from(files)[0];
    if (file) await importFile(file);
  }

  async function removeRom(id: string) {
    setError(null);
    setNotice(null);
    try {
      await deleteRom(id);
      setResult(null);
      await refreshHistory(selectedRomId === id ? '' : selectedRomId);
      setNotice('Removed ROM from local history');
    } catch (err) {
      setError(`Could not remove ROM: ${errorText(err)}`);
    }
  }

  async function generateRom() {
    setError(null);
    setNotice(null);
    setResult(null);
    if (!selectedRom) {
      setError('Select a ROM first.');
      return;
    }
    if (validationErrors.length > 0) {
      setError(validationErrors[0]);
      return;
    }

    setBusy(true);
    try {
      const record = await getRom(selectedRom.id);
      if (!record) {
        setError('Selected ROM was not found in local history.');
        await refreshHistory();
        return;
      }

      const patchInput = {
        serviceUrl,
        rom: record.blob,
        romFilename: record.name,
        build: buildRequest,
        randomize: randomizationRequest,
      };
      const baseName = record.name.replace(/\.(smc|sfc)$/i, '');

      if (outputMode === 'ips') {
        const ipsBlob = await patchIps(patchInput);
        downloadBlob(ipsBlob, `${baseName}-smedit.ips`);
        setNotice('Generated IPS patch');
        return;
      }

      const response = await patchRom(patchInput);
      const romBlob = base64ToBlob(response.romBase64, 'application/octet-stream');
      const ipsBlob = base64ToBlob(response.ipsBase64, 'application/octet-stream');
      setResult({ response, romBlob, ipsBlob, baseName });
      downloadBlob(romBlob, `${baseName}-smedit.smc`);
      setNotice('Generated patched ROM');
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDragging(false);
    void handleFiles(event.dataTransfer.files);
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">SMEDIT Service</p>
          <h1>ROM Patch Builder</h1>
        </div>
        <label className="service-field">
          <span>API</span>
          <input value={serviceUrl} onChange={(event) => setServiceUrl(event.target.value)} />
        </label>
      </header>

      <section className="workspace">
        <aside className="sidebar" aria-label="ROM history">
          <div
            className={`drop-zone ${isDragging ? 'dragging' : ''}`}
            onDragEnter={(event) => {
              event.preventDefault();
              setDragging(true);
            }}
            onDragOver={(event) => event.preventDefault()}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            onClick={() => fileInputRef.current?.click()}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                fileInputRef.current?.click();
              }
            }}
            role="button"
            tabIndex={0}
          >
            <Upload size={24} />
            <strong>Drop .smc</strong>
            <span>or choose a ROM</span>
            <input
              ref={fileInputRef}
              hidden
              type="file"
              accept=".smc,.sfc"
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                if (event.target.files) void handleFiles(event.target.files);
                event.target.value = '';
              }}
            />
          </div>

          <div className="panel-header">
            <History size={18} />
            <h2>Local History</h2>
          </div>
          <div className="history-list">
            {history.length === 0 ? (
              <div className="empty-state">No ROMs stored</div>
            ) : (
              history.map((rom) => (
                <button
                  key={rom.id}
                  className={`history-item ${rom.id === selectedRomId ? 'selected' : ''}`}
                  onClick={() => setSelectedRomId(rom.id)}
                >
                  <FileArchive size={18} />
                  <span>
                    <strong>{rom.name}</strong>
                    <small>
                      {formatBytes(rom.size)} · {formatDate(rom.addedAt)}
                    </small>
                  </span>
                  <Trash2
                    size={17}
                    className="trash"
                    onClick={(event) => {
                      event.stopPropagation();
                      void removeRom(rom.id);
                    }}
                  />
                </button>
              ))
            )}
          </div>
        </aside>

        <section className="main-grid">
          <section className="panel">
            <div className="panel-header">
              <Check size={18} />
              <h2>Patch Toggles</h2>
            </div>
            <div className="option-grid">
              {visiblePatchOptions.map((option) => (
                <label key={option.id} className="checkbox-row">
                  <input
                    type="checkbox"
                    checked={enabledPatches[option.id]}
                    onChange={(event) =>
                      setEnabledPatches((current) => ({
                        ...current,
                        [option.id]: event.target.checked,
                      }))
                    }
                  />
                  <span>
                    <strong>{option.label}</strong>
                    <small>{option.section}</small>
                  </span>
                </label>
              ))}
            </div>
            {metadataError && <div className="inline-note">{metadataError}</div>}
            <div className="settings-grid">
              <NumberField label="Fanfare frames" value={fanfareFrames} min={1} max={9999} step={1} onChange={setFanfareFrames} />
              {enabledPatches.bombs && (
                <>
                  <NumberField label="Max bombs" value={bombMaxActive} min={1} max={5} step={1} onChange={setBombMaxActive} />
                  <NumberField label="Fuse frames" value={bombFuseFrames} min={1} max={9999} step={1} onChange={setBombFuseFrames} />
                  <NumberField label="Cooldown" value={bombCooldownFrames} min={0} max={255} step={1} onChange={setBombCooldownFrames} />
                  <NumberField
                    label="Explosion delay"
                    value={bombExplosionDelay}
                    min={1}
                    max={255}
                    step={1}
                    onChange={setBombExplosionDelay}
                  />
                </>
              )}
            </div>
          </section>

          <section className="panel">
            <div className="panel-header">
              <FileArchive size={18} />
              <h2>Colorize</h2>
              <label className="toggle">
                <input
                  type="checkbox"
                  checked={colorizeEnabled}
                  onChange={(event) => setColorizeEnabled(event.target.checked)}
                />
                <span />
              </label>
            </div>
            <div className="two-col">
              <label className="field">
                <span>Effect</span>
                <select value={colorEffect} onChange={(event) => setColorEffect(event.target.value)}>
                  {colorEffectOptions.map((effect) => (
                    <option key={effect.id} value={effect.id}>
                      {effect.name}
                    </option>
                  ))}
                </select>
              </label>
              <div className="stack">
                <label className="checkbox-row slim">
                  <input
                    type="checkbox"
                    checked={includeTilesets}
                    onChange={(event) => setIncludeTilesets(event.target.checked)}
                  />
                  <span>Area tilesets</span>
                </label>
                <label className="checkbox-row slim">
                  <input
                    type="checkbox"
                    checked={includeSprites}
                    onChange={(event) => setIncludeSprites(event.target.checked)}
                  />
                  <span>Sprite palettes</span>
                </label>
              </div>
            </div>
            <div className="two-col">
              <label className="field">
                <span>Tilesets</span>
                <input
                  value={tilesetFilter}
                  placeholder="0,7,10"
                  onChange={(event) => setTilesetFilter(event.target.value)}
                />
              </label>
              <label className="field">
                <span>Sprite regions</span>
                <input
                  value={spriteRegionFilter}
                  placeholder="samus_power,boss_kraid"
                  onChange={(event) => setSpriteRegionFilter(event.target.value)}
                />
              </label>
            </div>
          </section>

          <section className="panel wide">
            <div className="panel-header">
              <Play size={18} />
              <h2>Combat Randomizer</h2>
              <label className="toggle">
                <input
                  type="checkbox"
                  checked={randomEnabled}
                  onChange={(event) => setRandomEnabled(event.target.checked)}
                />
                <span />
              </label>
            </div>

            <div className="controls-grid">
              <label className="field">
                <span>Preset</span>
                <select value={preset} onChange={(event) => setPreset(event.target.value)}>
                  {presetOptions.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>Seed</span>
                <input value={seed} placeholder="random" onChange={(event) => setSeed(event.target.value)} />
              </label>
              <label className="checkbox-row slim top-aligned">
                <input
                  type="checkbox"
                  checked={excludeMetroid}
                  onChange={(event) => setExcludeMetroid(event.target.checked)}
                />
                <span>Exclude Metroid</span>
              </label>
            </div>

            <ControlGroup title="Beam Filter">
              <CheckList values={beamOptions} selected={selectedBeams} onChange={setSelectedBeams} />
            </ControlGroup>

            <div className="two-col">
              <ControlGroup title="Include Categories">
                <CheckList values={categoryOptions} selected={includeCategories} onChange={setIncludeCategories} />
              </ControlGroup>
              <ControlGroup title="Exclude Categories">
                <CheckList values={categoryOptions} selected={excludeCategories} onChange={setExcludeCategories} />
              </ControlGroup>
            </div>

            <label className="field">
              <span>Extra excluded enemies</span>
              <input
                value={extraExcludedEnemies}
                placeholder="zoomer,ripper"
                onChange={(event) => setExtraExcludedEnemies(event.target.value)}
              />
            </label>

            <div className="randomizer-grid">
              <RangePanel enabled={overrideBeamDamage} onEnabledChange={setOverrideBeamDamage} title="Beam Damage">
                <NumberField label="Min rate" value={beamDamageMin} min={0} step={0.05} onChange={setBeamDamageMin} />
                <NumberField label="Max rate" value={beamDamageMax} min={0} step={0.05} onChange={setBeamDamageMax} />
              </RangePanel>

              <RangePanel enabled={overrideEnemyStats} onEnabledChange={setOverrideEnemyStats} title="Enemy Stats">
                <NumberField label="HP min" value={enemyHpMin} min={0} step={0.05} onChange={setEnemyHpMin} />
                <NumberField label="HP max" value={enemyHpMax} min={0} step={0.05} onChange={setEnemyHpMax} />
                <NumberField label="DMG min" value={enemyDamageMin} min={0} step={0.05} onChange={setEnemyDamageMin} />
                <NumberField label="DMG max" value={enemyDamageMax} min={0} step={0.05} onChange={setEnemyDamageMax} />
                <label className="checkbox-row slim">
                  <input
                    type="checkbox"
                    checked={randomizeHp}
                    onChange={(event) => setRandomizeHp(event.target.checked)}
                  />
                  <span>HP</span>
                </label>
                <label className="checkbox-row slim">
                  <input
                    type="checkbox"
                    checked={randomizeContactDamage}
                    onChange={(event) => setRandomizeContactDamage(event.target.checked)}
                  />
                  <span>Contact</span>
                </label>
              </RangePanel>

              <RangePanel enabled={overrideDrops} onEnabledChange={setOverrideDrops} title="Enemy Drops">
                <NumberField label="Nothing weight" value={dropNothingWeight} min={0} step={0.1} onChange={setDropNothingWeight} />
                <NumberField label="Min slots" value={dropMinNonZeroSlots} min={1} max={6} step={1} onChange={setDropMinNonZeroSlots} />
                <NumberField label="Max nothing" value={dropMaxNothing} min={0} max={255} step={1} onChange={setDropMaxNothing} />
              </RangePanel>

              <RangePanel
                enabled={overrideVulnerabilities}
                onEnabledChange={setOverrideVulnerabilities}
                title="Enemy Vulnerability"
              >
                <NumberField label="No effect" value={noEffectChance} min={0} max={1} step={0.01} onChange={setNoEffectChance} />
                <NumberField
                  label="Min effective"
                  value={minEffectiveWeapons}
                  min={0}
                  max={22}
                  step={1}
                  onChange={setMinEffectiveWeapons}
                />
                <label className="field">
                  <span>Multipliers</span>
                  <input value={multipliers} onChange={(event) => setMultipliers(event.target.value)} />
                </label>
                <label className="checkbox-row slim">
                  <input
                    type="checkbox"
                    checked={requireMissiles}
                    onChange={(event) => setRequireMissiles(event.target.checked)}
                  />
                  <span>Require slot 21</span>
                </label>
              </RangePanel>
            </div>
          </section>

          <section className="panel action-panel">
            <div className="selected-rom">
              <FileArchive size={20} />
              <span>
                <strong>{selectedRom?.name ?? 'No ROM selected'}</strong>
                <small>{selectedRom ? formatBytes(selectedRom.size) : 'Upload or select from history'}</small>
              </span>
            </div>
            <label className="field output-mode">
              <span>Output</span>
              <select value={outputMode} onChange={(event) => setOutputMode(event.target.value as OutputMode)}>
                <option value="rom">Patched ROM + IPS</option>
                <option value="ips">IPS only</option>
              </select>
            </label>
            <button
              className="primary-button"
              disabled={busy || !selectedRom || validationErrors.length > 0}
              onClick={() => void generateRom()}
            >
              {busy ? <Loader2 className="spin" size={20} /> : <Play size={20} />}
              {outputMode === 'ips' ? 'Generate IPS' : 'Generate ROM'}
            </button>
            {result && (
              <div className="download-row">
                <button onClick={() => downloadBlob(result.romBlob, `${result.baseName}-smedit.smc`)}>
                  <Download size={17} />
                  ROM
                </button>
                <button onClick={() => downloadBlob(result.ipsBlob, `${result.baseName}-smedit.ips`)}>
                  <Download size={17} />
                  IPS
                </button>
              </div>
            )}
            {error && <div className="message error">{error}</div>}
            {notice && <div className="message notice">{notice}</div>}
            {validationErrors.length > 0 && (
              <div className="message error">
                {validationErrors.map((message) => (
                  <p key={message}>{message}</p>
                ))}
              </div>
            )}
            {result && (
              <div className="report-strip">
                <span>{result.response.report.changedBytes.toLocaleString()} changed bytes</span>
                <span>{result.response.report.applied.length} applied</span>
                <span>{result.response.report.warnings.length} warnings</span>
              </div>
            )}
          </section>

          <section className="panel preview-panel">
            <div className="panel-header">
              <FileArchive size={18} />
              <h2>Request JSON</h2>
            </div>
            <pre>{previewJson}</pre>
          </section>
        </section>
      </section>
    </main>
  );
}

function ControlGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="control-group">
      <h3>{title}</h3>
      {children}
    </div>
  );
}

function CheckList({
  values,
  selected,
  onChange,
}: {
  values: string[];
  selected: string[];
  onChange: (selected: string[]) => void;
}) {
  return (
    <div className="chip-grid">
      {values.map((value) => (
        <label key={value} className={`chip ${selected.includes(value) ? 'active' : ''}`}>
          <input
            type="checkbox"
            checked={selected.includes(value)}
            onChange={(event) => {
              onChange(event.target.checked ? [...selected, value] : selected.filter((item) => item !== value));
            }}
          />
          <span>{value}</span>
        </label>
      ))}
    </div>
  );
}

function RangePanel({
  enabled,
  onEnabledChange,
  title,
  children,
}: {
  enabled: boolean;
  onEnabledChange: (enabled: boolean) => void;
  title: string;
  children: ReactNode;
}) {
  return (
    <div className={`range-panel ${enabled ? 'enabled' : ''}`}>
      <label className="checkbox-row slim">
        <input type="checkbox" checked={enabled} onChange={(event) => onEnabledChange(event.target.checked)} />
        <span>{title}</span>
      </label>
      <div className="range-fields">{children}</div>
    </div>
  );
}

function NumberField({
  label,
  value,
  min,
  max,
  step,
  onChange,
}: {
  label: string;
  value: number;
  min?: number;
  max?: number;
  step?: number;
  onChange: (value: number) => void;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input
        type="number"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </label>
  );
}

function parseStringList(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function parseIntegerList(value: string): number[] {
  return parseIntegerListDetailed(value).values;
}

function parseIntegerListDetailed(value: string): { values: number[]; invalid: string[] } {
  const values: number[] = [];
  const invalid: string[] = [];
  for (const item of parseStringList(value)) {
    if (!/^-?\d+$/.test(item)) {
      invalid.push(item);
      continue;
    }
    const parsed = Number(item);
    if (!Number.isSafeInteger(parsed)) {
      invalid.push(item);
      continue;
    }
    values.push(parsed);
  }
  return { values, invalid };
}

function parseOptionalInteger(value: string): number | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  if (!/^-?\d+$/.test(trimmed)) return undefined;
  const parsed = Number(trimmed);
  return Number.isSafeInteger(parsed) ? parsed : undefined;
}

function validateOrderedRange(label: string, min: number, max: number, floor: number): string[] {
  if (!Number.isFinite(min) || !Number.isFinite(max)) {
    return [`${label} must use finite numbers.`];
  }
  if (min < floor || max < floor || max < min) {
    return [`${label} min/max must be at least ${floor} and ordered.`];
  }
  return [];
}

function validateIntegerField(label: string, value: number, min: number, max: number): string[] {
  if (!Number.isFinite(value) || !Number.isInteger(value) || value < min || value > max) {
    return [`${label} must be an integer from ${min} to ${max}.`];
  }
  return [];
}

function loadPersistedSettings(): PersistedSettings {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.localStorage.getItem(settingsStorageKey);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    return isRecord(parsed) ? (parsed as PersistedSettings) : {};
  } catch {
    return {};
  }
}

function savePersistedSettings(settings: PersistedSettings): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(settingsStorageKey, JSON.stringify(settings));
  } catch {
    // Local settings are a convenience; failing to persist should not block ROM generation.
  }
}

function mergePatchSettings(saved: Partial<Record<PatchId, boolean>> | undefined): Record<PatchId, boolean> {
  return Object.fromEntries(
    patchOptions.map((option) => [option.id, typeof saved?.[option.id] === 'boolean' ? saved[option.id] : !!option.defaultEnabled]),
  ) as Record<PatchId, boolean>;
}

function numberSetting(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function booleanSetting(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

function stringArraySetting(value: unknown, fallback: string[]): string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string') ? value : [...fallback];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function formatDate(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(timestamp);
}
