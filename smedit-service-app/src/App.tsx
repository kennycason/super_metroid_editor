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
  { id: 'higher_jump', label: 'Higher jump', section: 'Movement' },
  { id: 'energy_free_shinesparks', label: 'Energy-free shinesparks', section: 'Movement' },
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

type ResultState = {
  response: ServicePatchResponse;
  romBlob: Blob;
  ipsBlob: Blob;
  baseName: string;
};

export function App() {
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [serviceUrl, setServiceUrl] = useState(serviceUrlDefault);
  const [history, setHistory] = useState<RomHistorySummary[]>([]);
  const [selectedRomId, setSelectedRomId] = useState<string>('');
  const [metadata, setMetadata] = useState<ServiceMetadata | null>(null);
  const [metadataError, setMetadataError] = useState<string | null>(null);
  const [isDragging, setDragging] = useState(false);
  const [enabledPatches, setEnabledPatches] = useState<Record<PatchId, boolean>>(defaultPatches);
  const [fanfareFrames, setFanfareFrames] = useState(16);
  const [outputMode, setOutputMode] = useState<OutputMode>('rom');

  const [colorizeEnabled, setColorizeEnabled] = useState(true);
  const [colorEffect, setColorEffect] = useState('psychedelic');
  const [includeTilesets, setIncludeTilesets] = useState(true);
  const [includeSprites, setIncludeSprites] = useState(true);
  const [tilesetFilter, setTilesetFilter] = useState('');
  const [spriteRegionFilter, setSpriteRegionFilter] = useState('');

  const [randomEnabled, setRandomEnabled] = useState(true);
  const [preset, setPreset] = useState('spicy');
  const [seed, setSeed] = useState('');
  const [selectedBeams, setSelectedBeams] = useState<string[]>(['power', 'ice', 'wave', 'plasma']);
  const [excludeMetroid, setExcludeMetroid] = useState(true);
  const [extraExcludedEnemies, setExtraExcludedEnemies] = useState('');
  const [includeCategories, setIncludeCategories] = useState<string[]>([]);
  const [excludeCategories, setExcludeCategories] = useState<string[]>(['Special']);

  const [overrideBeamDamage, setOverrideBeamDamage] = useState(false);
  const [beamDamageMin, setBeamDamageMin] = useState(0.5);
  const [beamDamageMax, setBeamDamageMax] = useState(2.75);
  const [overrideEnemyStats, setOverrideEnemyStats] = useState(false);
  const [enemyHpMin, setEnemyHpMin] = useState(0.5);
  const [enemyHpMax, setEnemyHpMax] = useState(3.5);
  const [enemyDamageMin, setEnemyDamageMin] = useState(0.5);
  const [enemyDamageMax, setEnemyDamageMax] = useState(2.25);
  const [randomizeHp, setRandomizeHp] = useState(true);
  const [randomizeContactDamage, setRandomizeContactDamage] = useState(true);
  const [overrideDrops, setOverrideDrops] = useState(false);
  const [dropNothingWeight, setDropNothingWeight] = useState(5);
  const [dropMinNonZeroSlots, setDropMinNonZeroSlots] = useState(2);
  const [dropMaxNothing, setDropMaxNothing] = useState(220);
  const [overrideVulnerabilities, setOverrideVulnerabilities] = useState(false);
  const [noEffectChance, setNoEffectChance] = useState(0.25);
  const [minEffectiveWeapons, setMinEffectiveWeapons] = useState(2);
  const [multipliers, setMultipliers] = useState('1,2,4,8');
  const [requireMissiles, setRequireMissiles] = useState(true);

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
      patches[option.id] =
        option.id === 'fanfares'
          ? { enabled: true, config: { item_fanfare_frames: fanfareFrames } }
          : { enabled: true };
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
        return current || items[0]?.id || '';
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
            <label className="field compact">
              <span>Fanfare frames</span>
              <input
                type="number"
                min={1}
                max={600}
                value={fanfareFrames}
                onChange={(event) => setFanfareFrames(Number(event.target.value))}
              />
            </label>
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
