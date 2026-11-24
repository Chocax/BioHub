# Política de seguridad

BioHub es un proyecto académico (Ingeniería de Software 2, Universidad El Bosque). Aun así, protege datos sensibles:
la ubicación de especies en peligro va cifrada y nunca sale al portal público.

## Versiones con soporte

| Versión | Soporte |
|---|---|
| `main` (última entrega) | Sí |
| Entregas anteriores | No |

## Cómo reportar una vulnerabilidad

No abras un issue público. Usa **Security → Report a vulnerability** en este repositorio (reporte privado de GitHub)
y cuenta:

- qué componente afecta (gateway, un servicio, una web, Keycloak o la infraestructura);
- cómo reproducirlo, con la petición o los pasos;
- qué se expone (por ejemplo, la ubicación de un hallazgo protegido o un rol que no corresponde).

Respondemos en un máximo de 7 días y avisamos cuando quede corregido.

## Lo que ya vigila el repositorio

- Escaneo de secretos con bloqueo al hacer push.
- CodeQL en Java, JavaScript y GitHub Actions.
- Alertas y actualizaciones de seguridad de Dependabot.
- `.env`, `certs/`, `*.pem` y `*.p12` nunca se suben (están en `.gitignore`).

El detalle de los controles está en [docs/equipo/08-reporte-seguridad.md](docs/equipo/08-reporte-seguridad.md) y
[docs/equipo/11-seguridad-y-acceso.md](docs/equipo/11-seguridad-y-acceso.md).
