export type PatchId =
  | 'vanilla_bugfixes'
  | 'skip_intro_and_ceres'
  | 'skip_intro'
  | 'ceres_escape_seconds'
  | 'fanfares'
  | 'bombs'
  | 'higher_jump'
  | 'energy_free_shinesparks'
  | 'enable_moonwalk'
  | 'fast_doors'
  | 'fast_elevators'
  | 'infinite_missiles'
  | 'infinite_super_missiles'
  | 'infinite_power_bombs'
  | 'infinite_blue_suit'
  | 'hyper_beam';

export type PatchOption = {
  id: PatchId;
  label: string;
  defaultEnabled?: boolean;
  section: 'Start' | 'Movement' | 'Supplies' | 'Combat';
};

export type BuildRequest = {
  schemaVersion: 1;
  strictConfigValidation?: boolean;
  patches?: Record<string, PatchRequest>;
  colorize?: ColorizeRequest;
};

export type PatchRequest = {
  enabled?: boolean;
  config?: Record<string, number>;
};

export type ColorizeRequest = {
  effect: string;
  seed?: number;
  includeTilesets?: boolean;
  includeSprites?: boolean;
  tilesets?: number[];
  spriteRegions?: string[];
};

export type RandomizationRequest = {
  seed?: number;
  preset?: string;
  includeBeams?: string[];
  excludeEnemies?: string[];
  includeEnemyCategories?: string[];
  excludeEnemyCategories?: string[];
  beamDamage?: {
    enabled: boolean;
    damageMin: number;
    damageMax: number;
  };
  enemyStats?: {
    enabled: boolean;
    randomizeHp: boolean;
    randomizeContactDamage: boolean;
    enemyHpMin: number;
    enemyHpMax: number;
    enemyDamageMin: number;
    enemyDamageMax: number;
    preserveOneHpEnemies: boolean;
    preserveZeroDamageEnemies: boolean;
  };
  enemyDrops?: {
    enabled: boolean;
    total: number;
    smallEnergyWeight: number;
    largeEnergyWeight: number;
    missileWeight: number;
    nothingWeight: number;
    superMissileWeight: number;
    powerBombWeight: number;
    minNonZeroSlots: number;
    maxNothing: number;
  };
  enemyVulnerabilities?: {
    enabled: boolean;
    noEffectChance: number;
    multipliers: number[];
    ensureAtLeastOneEffectivePerEnemy: boolean;
    minEffectiveWeaponsPerEnemy: number;
    requiredEffectiveWeaponSlots: number[];
  };
};

export type StoredRomItem = {
  id: string;
  name: string;
  size: number;
  lastModified: number;
  addedAt: number;
  blob: Blob;
};

export type StoredRomSummary = Omit<StoredRomItem, 'blob'>;

export type ServicePatchResponse = {
  romBase64: string;
  ipsBase64: string;
  report: BuildReport;
  resolvedBuild?: BuildRequest;
  randomization?: RandomizationReport;
};

export type BuildReport = {
  mode: string;
  inputRomBytes: number;
  outputRomBytes: number;
  changedBytes: number;
  patchBytes: number;
  applied: Array<{
    identifier: string;
    name: string;
    source: string;
    configType?: string;
    writes: number;
    bytes: number;
  }>;
  warnings: string[];
};

export type RandomizationReport = {
  seed: number;
  preset?: string;
  randomizedConfigTypes: string[];
  randomizedFieldCounts: Record<string, number>;
};

export type ServiceMetadata = {
  schemaVersion: number;
  patches: ServicePatchMetadata[];
  configSchemas: ConfigSchema[];
  randomization: {
    presets: string[];
    beams: string[];
    enemyCategories: string[];
    enemies: Array<{
      key: string;
      label: string;
      category: string;
    }>;
  };
  colorize: {
    effects: Array<{
      id: string;
      name: string;
    }>;
    tilesetCount: number;
    spriteRegions: Array<{
      id: string;
      name: string;
      category: string;
      colors: number;
    }>;
  };
};

export type ServicePatchMetadata = {
  id: string;
  internalId: string;
  name: string;
  description?: string;
  configType?: string;
  aliases?: string[];
  headlessSupported: boolean;
  supportsPatchOnly: boolean;
  requiresRom: boolean;
};

export type ConfigSchema = {
  configType: string;
  patchId: string;
  name: string;
  description: string;
  headlessSupported: boolean;
  supportsPatchOnly: boolean;
  requiresRom: boolean;
  fields: ConfigField[];
};

export type ConfigField = {
  key: string;
  label: string;
  type: string;
  min: number;
  max: number;
  defaultValue?: number;
  description?: string;
  category?: string;
  unit?: string;
  signed?: boolean;
  logicalMin?: number;
  logicalMax?: number;
  requiresRom?: boolean;
  choices?: Array<{ label: string; value: number }>;
};
