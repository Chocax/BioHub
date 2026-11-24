## Qué cambia

<!-- Una o dos frases: qué hace este PR y por qué. -->

## Historia o tarea

<!-- HU-xx, ASR o el hallazgo de la revisión que resuelve. -->

## Cómo se probó

- [ ] `mvn verify` del servicio que cambió (o `npm test` / `npm run build` en la web)
- [ ] Probado a mano con `levantar.ps1` (si toca pantallas o el flujo completo)
- [ ] Colección de Bruno al día (si cambia un endpoint)

## Antes de pedir la fusión

- [ ] La rama base es `develop` (solo `develop` va a `main`)
- [ ] No sube `.env`, `certs/`, `*.pem` ni `*.p12`
- [ ] Si toca una zona compartida (`pom.xml` raíz, `docker-compose.yml`, `infra/`, el realm o `.github/`), está avisado al equipo
- [ ] `CHANGELOG.md` actualizado si el cambio se nota
