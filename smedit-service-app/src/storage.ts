import type { RomHistoryItem, RomHistorySummary } from './types';

const DB_NAME = 'smedit-service-app';
const DB_VERSION = 1;
const ROM_STORE = 'roms';

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(ROM_STORE)) {
        const store = db.createObjectStore(ROM_STORE, { keyPath: 'id' });
        store.createIndex('addedAt', 'addedAt');
      }
    };
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
  });
}

async function withStore<T>(
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => IDBRequest<T> | void,
): Promise<T | undefined> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(ROM_STORE, mode);
    const store = transaction.objectStore(ROM_STORE);
    const request = operation(store);
    let result: T | undefined;

    if (request) {
      request.onsuccess = () => {
        result = request.result;
      };
      request.onerror = () => reject(request.error);
    }

    transaction.oncomplete = () => {
      db.close();
      resolve(result);
    };
    transaction.onerror = () => {
      db.close();
      reject(transaction.error);
    };
  });
}

export function romIdForFile(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`;
}

export async function saveRomFile(file: File): Promise<RomHistorySummary> {
  const item: RomHistoryItem = {
    id: romIdForFile(file),
    name: file.name,
    size: file.size,
    lastModified: file.lastModified,
    addedAt: Date.now(),
    blob: file,
  };
  await withStore('readwrite', (store) => store.put(item));
  const { blob: _blob, ...summary } = item;
  return summary;
}

export async function listRoms(): Promise<RomHistorySummary[]> {
  const items = (await withStore<RomHistoryItem[]>('readonly', (store) => store.getAll())) ?? [];
  return items
    .map(({ blob: _blob, ...summary }) => summary)
    .sort((a, b) => b.addedAt - a.addedAt);
}

export async function getRom(id: string): Promise<RomHistoryItem | undefined> {
  return withStore<RomHistoryItem>('readonly', (store) => store.get(id));
}

export async function deleteRom(id: string): Promise<void> {
  await withStore('readwrite', (store) => store.delete(id));
}
