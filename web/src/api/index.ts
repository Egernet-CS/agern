import type { Api } from './api';
import { httpApi } from './http';

export * from './types';
export { ApiError } from './api';
export type { Api, Channel, ChannelHandlers } from './api';

/**
 * The active client. Defaults to the real backend; main.tsx swaps in the mock (VITE_MOCK=1)
 * before the app renders. The mock lives in src/mock and is never bundled otherwise.
 */
export let api: Api = httpApi;
export const isMock = import.meta.env.VITE_MOCK === '1';

export function installApi(impl: Api) {
  api = impl;
}
