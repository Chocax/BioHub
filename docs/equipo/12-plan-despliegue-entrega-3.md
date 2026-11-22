# 12 · Plan de despliegue para la Entrega 3 (implementación y cierre)

La Entrega 3 pide "el sistema funcional, con las decisiones de arquitectura aplicadas, las pruebas y la sustentación". Este plan dice **qué corre dónde el día de la sustentación**, en qué orden se prepara, cómo se comprueba que quedó bien y qué hacer si algo falla. Lo técnico de cada pieza (perfiles, Aiven, truststore, CI) está en [09-despliegue.md](09-despliegue.md); aquí no se repite.

Los puntos que dependen de una decisión del equipo están marcados **Por decidir**.

## 1. Topología de la entrega

No hay servidores propios: desde la iteración 15 se retiraron las VMs y la VPN (09 §1). El sistema corre en el portátil de quien presenta, con las bases de verdad en Aiven:

| Pieza | Dónde corre | Cómo se levanta |
|---|---|---|
| 5 servicios Spring Boot | Portátil de quien presenta, en `127.0.0.1` | `levantar.ps1 -Perfil aiven` (Windows) o `./levantar.sh --perfil aiven` (Linux) |
| Bases MySQL | Aiven: servicio de escritura (`cientifico`, `equipos`) y de lectura (`consulta_publica`, `reportes`, `gateway`), con SSL y CA verificado | Ya creadas (09 §4). Usuarios `*_app` y `*_migr` separados |
| RabbitMQ, Redis, Keycloak | Docker en el mismo portátil | Los sube `levantar` |
| Webs | El mismo portátil, **compiladas** (`dist/`) y solo en `localhost` | Ver §4, paso 6 |
| Plan B sin internet | Todo en el portátil, bases en Docker (perfil `local`) con datos de ejemplo | `levantar.ps1 -Sembrar` |

**Por qué así:** es lo que ya está probado (CI, Bruno, E2E y la verificación contra Aiven del 2026-11-04, 09 §4.5) y cumple el enunciado sin inventar infraestructura nueva ("mejor una solución acotada y bien sustentada"). Las decisiones de arquitectura (gateway propio, CQRS con RabbitMQ, base analítica aparte, cifrado de coordenadas, historial append-only) se ven igual en el portátil que en un servidor.

**Por decidir:** quién presenta. Ese portátil es el que se prepara con todo lo de §3 (Java 21, Docker, `.env` y `certs/` con los dos CA de Aiven).

## 2. Qué se muestra (mapa contra los desafíos del enunciado)

| Desafío del enunciado | Qué se muestra | Evidencia que lo respalda |
|---|---|---|
| Coordenadas de especies en peligro | Mapa con coordenadas solo para `cientifico_alto_rango`; el portal nunca las tiene; fotos y audios sin GPS en los metadatos | `ArquitecturaSeguridadTest`, E2E WI-10, `LimpiadorMetadatosTest`, `LimpiadorAudioTest` |
| Pico de tráfico en la consulta pública | Portal con caché Redis y modo degradado | k6 ASR1 y ASR4 (`pruebas-carga/`, criterios D42). **Pendiente:** mediciones nuevas de Juanita con la portada de especies |
| Historial que no se puede alterar | Reclasificar crea una versión; el usuario de la app no puede borrar ni modificar el historial (error 1142) | `deploy/aiven/03-...` aplicado en Aiven; en local lo aplica `levantar` y lo comprueba |
| Plataforma abierta con límite por consumidor | API Keys, 429 con `Retry-After`, key revocada rechazada desde la siguiente petición | k6 `gateway-limite-api-externa.js` (ASR8) |
| Reportes que ahogan el sistema | Reporte mensual en segundo plano sobre la base analítica, PDF y CSV | **Pendiente:** medición RNF3 de Nicolás (registro sin degradarse más de 10 % durante un reporte) |

Lo que sigue **pendiente** al cierre de este documento está en la revisión del equipo del 2026-11-21 (00-plan) y no bloquea el despliegue, pero sí la sustentación de ese punto.

## 3. Preparación (una semana antes)

| # | Qué | Quién | Cómo se comprueba |
|---|---|---|---|
| 1 | `develop` con todo fusionado y la CI en verde (frontend, web-interna, backend x5, bruno) | Cada dueño su PR; Santiago revisa zonas compartidas | Pestaña Actions de GitHub |
| 2 | Migraciones de los 5 servicios aplicadas en Aiven, con su usuario `*_migr` | Dueño de cada servicio | Log de arranque: `Successfully applied` o `Schema ... is up to date` (09 §4.6) |
| 3 | Historial bloqueado en Aiven (`03-registro-historial-append-only.sql.example`) después de la última migración de `cientifico` | Dueña del servicio de escritura de Aiven | Con `cientifico_app`: `DELETE FROM hallazgo_version LIMIT 0` responde `ERROR 1142` |
| 4 | Misma llave de coordenadas (`UBICACION_CLAVES`) en el `.env` de quien presenta que la usada para cifrar los datos de Aiven | Juanita la entrega por canal privado | El mapa muestra coordenadas a `cientifico1` (si la llave no coincide, el servicio no las descifra) |
| 5 | `.env` y `certs/` del portátil de la sustentación con las credenciales de Aiven de las 5 bases y el truststore con los 2 CA | Quien presenta, con cada dueño | `levantar.ps1 -Perfil aiven` llega a "Listo" sin avisos |
| 6 | Datos de la demostración en Aiven: hallazgos de varias especies y departamentos, al menos una foto marcada como pública, un consumidor con API Key, un reporte del mes anterior | Equipo | Recorrido de §5 completo |
| 7 | Ensayo completo con cronómetro, en el portátil y la red del día | Equipo | §5 sin fallos dos veces seguidas |
| 8 | Plan B probado sin internet: `levantar.ps1 -Sembrar` con el wifi apagado | Quien presenta | Recorrido de §5 con datos de ejemplo |

**Por decidir:** si los datos de la demostración (punto 6) se cargan a mano o con un script parecido a `scripts/sembrar-demo.mjs` apuntando a Aiven. Hoy `levantar` no lo corre con `-Perfil aiven` a propósito, porque la base es compartida.

## 4. El día de la sustentación (orden)

1. **Una hora antes**, en la red del lugar: comprobar que el portátil llega a Aiven. La IP pública cambia según la red, y Aiven solo deja entrar a las IP permitidas: si la red es nueva, la dueña de cada servicio de Aiven agrega la IP en *Allowed IP addresses*. Este es el fallo más probable del día.
2. `git pull` de `develop` (o de la etiqueta de la entrega) y `.\levantar.ps1 -Perfil aiven -Compilar`.
3. Revisar que la tabla final esté toda en "arriba" y que no haya `OJO` ni `FALLA` en la salida.
4. Entrar a la web interna con cada rol que se va a mostrar y al portal público.
5. Abrir aparte la colección Bruno y los resultados de k6 (`pruebas-carga/resultados/`), por si piden evidencia.
6. **Webs compiladas:** la CSP (cabecera de seguridad de las dos webs) solo existe en el build, no en `vite dev`. Para mostrarla, después del paso 2 se cambia cada web a su build, siempre solo en `localhost` y nunca con `--host` (09 §9):

   ```powershell
   .\levantar.ps1 -Detener                        # apaga servicios y webs, conserva datos
   .\levantar.ps1 -Perfil aiven                   # vuelve a subir todo
   # en otra terminal, por cada web (cierra antes la de vite dev de ese puerto):
   cd web-interna; npm run build; npx vite preview --port 5173 --strictPort
   cd web-publica; npm run build; npx vite preview --port 5174 --strictPort
   ```

   **Por decidir:** si vale la pena que `levantar` tenga una opción para servir las webs compiladas directamente (hoy no la tiene).

## 5. Recorrido de verificación (también es el guion del ensayo)

1. Portal público (sin login): catálogo por especie con foto, ficha sin coordenadas, búsqueda por familia y departamento.
2. `investigador1`: registrar un hallazgo con foto y audio, marcar la foto como pública, reclasificar con justificación.
3. Portal público: la foto aparece en la especie en un par de minutos (la caché recuerda "sin foto" durante 2 minutos, ADR D65).
4. `cientifico1`: mapa con coordenadas exactas del hallazgo nuevo.
5. `auditor1`: historial con las dos versiones, comparación y auditoría campo a campo, sin botones de edición.
6. `analista1`: pedir el reporte de un mes, seguir trabajando mientras se genera y descargar PDF y CSV.
7. `admin1`: crear un consumidor y su API Key, llamar a `/api/v1/externa/fichas` con la key hasta recibir 429, revocar la key y ver el 401; cambiar el rol de un usuario.

## 6. Si algo falla (vuelta atrás)

| Síntoma | Qué hacer |
|---|---|
| No conecta a Aiven (`Communications link failure`, `Access denied`) | Revisar la IP permitida (§4.1). Si no se resuelve en 5 minutos: **plan B**, `levantar.ps1 -Detener` y `levantar.ps1 -Sembrar` (perfil local con datos de ejemplo) |
| Un servicio no arranca | `levantar` muestra la causa y el log en `.biohub-local/logs/<servicio>.log`. Volver a la etiqueta anterior que funcionó (`git checkout <etiqueta>`) y `-Compilar` |
| Una migración nueva rompió algo en Aiven | No se revierte sola: restaurar desde *Backups* de Aiven o crear un *fork* (09 §6). Por eso las migraciones se congelan una semana antes (§7) |
| Keycloak no deja entrar a los usuarios de prueba | `levantar.ps1 -ReimportarRealm` |
| Sin internet en el lugar | Plan B (perfil local), probado en §3.8 |

## 7. Congelamiento

- **Una semana antes:** no entran migraciones nuevas a `develop` salvo correcciones acordadas por el equipo. Cada migración que llegue después debe pasar `deploy/aiven/verificar-migraciones.sh` y, si crea tablas en `cientifico`, volver a correr el paso 3 de §3.
- **Dos días antes:** se etiqueta `develop` (por ejemplo `entrega-3`) y se fusiona a `main` por PR. La sustentación se hace desde esa etiqueta.

## 8. Fuera del alcance de esta entrega

Se dejan escritos para que nadie los dé por hechos:

- Keycloak sigue en modo desarrollo (`start-dev`, H2) y con el cliente `biohub-pruebas` (password grant), que en producción se elimina.
- No hay HTTPS entre los servicios y Keycloak o RabbitMQ (todo en `127.0.0.1`).
- No hay servidores propios ni despliegue de las webs en internet.
- Un despliegue real necesitaría:
  - las imágenes de los servicios (`<servicio>/Dockerfile` ya existe);
  - una red interna donde solo el gateway sea público;
  - `SERVER_ADDRESS=0.0.0.0` dentro de esa red, y `SERVICIO_*_URL` apuntando a esa red;
  - `frame-ancestors` como cabecera HTTP de las webs.
