import type { BuildRequest, RandomizationRequest, ServicePatchResponse } from './types';

export type PatchServiceInput = {
  serviceUrl: string;
  rom: Blob;
  romFilename?: string;
  build: BuildRequest;
  randomize?: RandomizationRequest;
};

export async function patchRom(input: PatchServiceInput): Promise<ServicePatchResponse> {
  const form = new FormData();
  form.append('rom', input.rom, input.romFilename ?? 'base.smc');
  form.append('build', JSON.stringify(stripEmpty(input.build)));
  if (input.randomize) {
    form.append('randomize', JSON.stringify(stripEmpty(input.randomize)));
  }

  const response = await fetch(`${input.serviceUrl.replace(/\/+$/, '')}/patch?format=json`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
    },
    body: form,
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(errorMessageFromResponse(text, response.status));
  }

  return response.json() as Promise<ServicePatchResponse>;
}

export function base64ToBlob(base64: string, contentType: string): Blob {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new Blob([bytes], { type: contentType });
}

export function downloadBlob(blob: Blob, filename: string): void {
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(href);
}

export function stripEmpty<T>(value: T): T {
  if (Array.isArray(value)) {
    return value.filter((item) => item !== undefined && item !== null && item !== '') as T;
  }
  if (value && typeof value === 'object') {
    const result: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) {
      if (item === undefined || item === null || item === '') continue;
      if (Array.isArray(item) && item.length === 0) continue;
      if (typeof item === 'object' && !Array.isArray(item)) {
        const nested = stripEmpty(item);
        if (Object.keys(nested as Record<string, unknown>).length === 0) continue;
        result[key] = nested;
      } else {
        result[key] = item;
      }
    }
    return result as T;
  }
  return value;
}

function errorMessageFromResponse(text: string, status: number): string {
  try {
    const parsed = JSON.parse(text) as { error?: string };
    return parsed.error || `SMEDIT service returned HTTP ${status}`;
  } catch {
    return text || `SMEDIT service returned HTTP ${status}`;
  }
}
