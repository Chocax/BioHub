# Revisión 2026-11-19: alcance

**Rama revisada:** `develop` (HEAD `8d23981`, PR #17). **Modo:** revisión de solo lectura (nadie modifica código). **Presupuesto:** equilibrado, sin tope.
**Revisión anterior:** `docs/revision/INFORME.md` (2026-11-08, HEAD `6a5495a`). Desde entonces entraron 96 commits (PR #8 a #17): registro, historial, equipos, reportes, evidencias, web-interna, web-publica, catálogo por especie.

## Qué se pide
1. Revisar los commits subidos desde la revisión anterior.
2. Comprobar RF, HU, RNF y ASR contra `docs/entregas/SRS-BioHub.txt` y `SAD-BioHub.txt`.
3. Arquitectura y patrones (estilo de servicios + gateway + eventos, CQRS, caché, circuit breaker, etc.).
4. Que todo funcione: build, tests y la app levantada de punta a punta.
5. Bases de escritura y lectura: que existan, estén separadas, sincronizadas por eventos y funcionando.

## Contexto
Proyecto académico (Ingeniería de Software 2, Entrega 3: sistema funcional + pruebas + sustentación). Enunciado en `docs/Enunciado original del proyecto.md`.

## Informes (formato común por hallazgo: ID, severidad, archivo:línea, problema, por qué importa, corrección, esfuerzo S/M/L; más una sección "Bien hecho")
| Informe | Responsable | Modelo / effort |
|---|---|---|
| `arquitectura-asr.md` | arquitecto-software | opus / high |
| `requisitos.md` | product-owner | sonnet / medium |
| `backend.md` | backend-senior | sonnet / high |
| `base-de-datos.md` | dba | sonnet / high |
| `frontend.md` | frontend-senior | sonnet / medium |
| `calidad.md` | qa-tester | sonnet / high (sube de medium: levanta y prueba todo el sistema) |
| `seguridad.md` | ciberseguridad | opus / high |
| `devops.md` | devops | sonnet / medium |

Omitidos: ux-ui-designer y documentador-tecnico (el pedido se centra en requisitos, arquitectura, funcionamiento y datos).
