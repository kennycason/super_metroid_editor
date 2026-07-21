export type PatchId =
  | 'vanilla_bugfixes'
  | 'skip_intro_and_ceres'
  | 'skip_intro'
  | 'fanfares'
  | 'higher_jump'
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
  includeTilesets?: boolean;
  includeSprites?: boolean;
  tilesets?: number[];
  spriteRegions?: string[];
};

export type RandomizationRequest = {
  seed?: number;
  preset?: 'balanced' | 'spicy' | 'chaos' | 'survival';
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

export type RomHistoryItem = {
  id: string;
  name: string;
  size: number;
  lastModified: number;
  addedAt: number;
  blob: Blob;
};

export type RomHistorySummary = Omit<RomHistoryItem, 'blob'>;

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
