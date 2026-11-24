import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../auth/keycloak.js', () => ({
  keycloak: { token: 'tk-1', updateToken: vi.fn().mockResolvedValue(true), login: vi.fn() },
}));

const { api } = await import('./cliente.js');

describe('cliente de la API', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('envía las cabeceras extra (Idempotency-Key) sin dejar que pisen el token', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{"hallazgoId":"h-1"}', { status: 201 }));
    vi.stubGlobal('fetch', fetch);

    await api.post('/api/cientifico/hallazgos', { a: 1 },
      { cabeceras: { 'Idempotency-Key': 'clave-1', Authorization: 'Bearer falso' } });

    const { headers } = fetch.mock.calls[0][1];
    expect(headers['Idempotency-Key']).toBe('clave-1');
    expect(headers.Authorization).toBe('Bearer tk-1');
    expect(headers['Content-Type']).toBe('application/json');
  });
});
