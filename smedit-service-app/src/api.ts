import type { BuildRequest, GeneratorRequest, RandomizationRequest, ServiceMetadata, ServicePatchResponse } from './types';

export type PatchServiceInput = {
  serviceUrl: string;
  rom: Blob;
  romFilename?: string;
  build: BuildRequest;
  randomize?: RandomizationRequest;
  generator?: GeneratorRequest;
};

export async function fetchMetadata(serviceUrl: string, signal?: AbortSignal): Promise<ServiceMetadata> {
  const baseUrl = serviceBaseUrl(serviceUrl);
  const response = await fetchService(baseUrl, '/metadata', {
    method: 'GET',
    headers: {
      Accept: 'application/json',
    },
    signal,
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(errorMessageFromResponse(text, response.status));
  }

  return response.json() as Promise<ServiceMetadata>;
}

export async function patchRom(input: PatchServiceInput): Promise<ServicePatchResponse> {
  const baseUrl = serviceBaseUrl(input.serviceUrl);
  const response = await fetchService(baseUrl, '/patch?format=json', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
    },
    body: patchForm(input),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(errorMessageFromResponse(text, response.status));
  }

  return response.json() as Promise<ServicePatchResponse>;
}

export async function patchIps(input: PatchServiceInput): Promise<Blob> {
  const baseUrl = serviceBaseUrl(input.serviceUrl);
  const response = await fetchService(baseUrl, '/patch?format=ips', {
    method: 'POST',
    body: patchForm(input),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(errorMessageFromResponse(text, response.status));
  }

  return response.blob();
}

function patchForm(input: PatchServiceInput): FormData {
  const form = new FormData();
  form.append('rom', input.rom, input.romFilename ?? 'base.smc');
  form.append('build', JSON.stringify(stripEmpty(input.build)));
  if (input.randomize) {
    form.append('randomize', JSON.stringify(stripEmpty(input.randomize)));
  }
  if (input.generator) {
    form.append('generator', JSON.stringify(stripEmpty(input.generator)));
  }
  return form;
}

function serviceBaseUrl(serviceUrl: string): string {
  return serviceUrl.replace(/\/+$/, '');
}

async function fetchService(baseUrl: string, path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(`${baseUrl}${path}`, init);
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new Error(
      `Cannot reach SMEDIT service at ${baseUrl}. Start the service or update the API URL. ` +
        `If it is running, check that the browser URL is allowed by the service CORS settings.`,
    );
  }
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
  const frameName = 'smedit-download-frame';
  let frame = document.querySelector<HTMLIFrameElement>(`iframe[name="${frameName}"]`);
  if (!frame) {
    frame = document.createElement('iframe');
    frame.name = frameName;
    frame.style.display = 'none';
    document.body.appendChild(frame);
  }

  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.download = filename;
  anchor.target = frameName;
  anchor.rel = 'noopener';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  window.setTimeout(() => {
    URL.revokeObjectURL(href);
    anchor.remove();
  }, 1000);
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
