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
import { base64ToBlob, downloadBlob, patchRom, stripEmpty } from './api';
import { deleteRom, getRom, listRoms, saveRomFile } from './storage';
import type {
  BuildRequest,
  PatchId,
  PatchOption,
  RandomizationRequest,
  RomHistorySummary,
  ServicePatchResponse,
} from './types';

const serviceUrlDefault = import.meta.env.VITE_SMEDIT_SERVICE_URL || 'http://localhost:8080';

const patchOptions: PatchOption[] = [
  { id: 'skip_intro_and_ceres', label: 'Skip Ceres + intro', section: 'Start', defaultEnabled: true },
  { id: 'skip_intro', label: 'Skip intro only', section: 'Start' },
  { id: 'vanilla_bugfixes', label: 'Vanilla bugfixes', section: 'Start' },
  { id: 'fanfares', label: 'Quick item fanfares', section: 'Start', defaultEnabled: true },
  { id: 'higher_jump', label: 'Higher jump', section: 'Movement' },
  { id: 'fast_doors', label: 'Fast doors', section: 'Movement' },
  { id: 'fast_elevators', label: 'Fast elevators', section: 'Movement' },
  { id: 'infinite_missiles', label: 'Infinite missiles', section: 'Supplies' },
  { id: 'infinite_super_missiles', label: 'Infinite supers', section: 'Supplies' },
  { id: 'infinite_power_bombs', label: 'Infinite power bombs', section: 'Supplies' },
  { id: 'hyper_beam', label: 'Hyper beam', section: 'Combat' },
  { id: 'infinite_blue_suit', label: 'Infinite blue suit', section: 'Combat' },
];

const colorEffects = [
  'psychedelic',
  'vaporwave',
  'acid',
  'cyberpunk',
  'rainbow',
  'thermal',
  'hologram',
  'pastel',
  'golden',
  'icecold',
  'lava',
  'underwater',
  'grayscale',
  'invert',
];

const beamOptions = ['power', 'ice', 'wave', 'spazer', 'plasma', 'iwp'];
const categoryOptions = ['Pirate', 'Flyer', 'Crawler', 'Boss', 'Special'];
const defaultPatches = Object.fromEntries(patchOptions.map((option) => [option.id, !!option.defaultEnabled])) as Record<
  PatchId,
  boolean
>;

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
  const [isDragging, setDragging] = useState(false);
  const [enabledPatches, setEnabledPatches] = useState<Record<PatchId, boolean>>(defaultPatches);
  const [fanfareFrames, setFanfareFrames] = useState(16);

  const [colorizeEnabled, setColorizeEnabled] = useState(true);
  const [colorEffect, setColorEffect] = useState('psychedelic');
  const [includeTilesets, setIncludeTilesets] = useState(true);
  const [includeSprites, setIncludeSprites] = useState(true);
  const [tilesetFilter, setTilesetFilter] = useState('');
  const [spriteRegionFilter, setSpriteRegionFilter] = useState('');

  const [randomEnabled, setRandomEnabled] = useState(true);
  const [preset, setPreset] = useState<'balanced' | 'spicy' | 'chaos' | 'survival'>('spicy');
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

  const selectedRom = history.find((item) => item.id === selectedRomId);

  const buildRequest = useMemo<BuildRequest>(() => {
    const patches: NonNullable<BuildRequest['patches']> = {};
    for (const option of patchOptions) {
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
  ]);

  const randomizationRequest = useMemo<RandomizationRequest | undefined>(() => {
    if (!randomEnabled) return undefined;

    const excludedEnemies = [...(excludeMetroid ? ['metroid'] : []), ...parseStringList(extraExcludedEnemies)];
    const request: RandomizationRequest = {
      preset,
      seed: seed.trim() ? Number(seed) : undefined,
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
    const items = await listRoms();
    setHistory(items);
    setSelectedRomId((current) => {
      if (nextSelectedId !== undefined) return nextSelectedId || items[0]?.id || '';
      return current || items[0]?.id || '';
    });
  }

  async function importFile(file: File) {
    setError(null);
    setNotice(null);
    if (!/\.(smc|sfc)$/i.test(file.name)) {
      setError('Use a .smc or .sfc ROM file.');
      return;
    }
    const summary = await saveRomFile(file);
    await refreshHistory(summary.id);
    setNotice(`Stored ${summary.name}`);
  }

  async function handleFiles(files: FileList | File[]) {
    const file = Array.from(files)[0];
    if (file) await importFile(file);
  }

  async function removeRom(id: string) {
    await deleteRom(id);
    setResult(null);
    await refreshHistory(selectedRomId === id ? '' : selectedRomId);
  }

  async function generateRom() {
    setError(null);
    setNotice(null);
    setResult(null);
    if (!selectedRom) {
      setError('Select a ROM first.');
      return;
    }

    const record = await getRom(selectedRom.id);
    if (!record) {
      setError('Selected ROM was not found in local history.');
      await refreshHistory();
      return;
    }

    setBusy(true);
    try {
      const response = await patchRom({
        serviceUrl,
        rom: record.blob,
        romFilename: record.name,
        build: buildRequest,
        randomize: randomizationRequest,
      });
      const baseName = record.name.replace(/\.(smc|sfc)$/i, '');
      const romBlob = base64ToBlob(response.romBase64, 'application/octet-stream');
      const ipsBlob = base64ToBlob(response.ipsBase64, 'application/octet-stream');
      setResult({ response, romBlob, ipsBlob, baseName });
      downloadBlob(romBlob, `${baseName}-smedit.smc`);
      setNotice('Generated patched ROM');
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
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
              {patchOptions.map((option) => (
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
                  {colorEffects.map((effect) => (
                    <option key={effect} value={effect}>
                      {effect}
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
                <select value={preset} onChange={(event) => setPreset(event.target.value as typeof preset)}>
                  <option value="balanced">balanced</option>
                  <option value="spicy">spicy</option>
                  <option value="chaos">chaos</option>
                  <option value="survival">survival</option>
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
            <button className="primary-button" disabled={busy || !selectedRom} onClick={() => void generateRom()}>
              {busy ? <Loader2 className="spin" size={20} /> : <Play size={20} />}
              Generate ROM
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
  return parseStringList(value)
    .map((item) => Number.parseInt(item, 10))
    .filter((item) => Number.isFinite(item));
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
