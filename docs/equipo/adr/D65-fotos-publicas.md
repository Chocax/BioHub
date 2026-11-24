# ADR D65 · Fotos públicas por especie

| | |
|---|---|
| **Estado** | Aceptada el 2026-11-19. **La parte de cómo llega la foto al portal la reemplazó [D66](../03-arquitectura.md) el 2026-11-21** (iteración 27). Siguen vigentes el catálogo por especie y que ninguna foto es pública sin que la marque quien registra |
| **Responsable** | Santiago (diseño), con Juanita (consulta pública) |
| **Resumen en la tabla** | [`03-arquitectura.md`, decisión D65](../03-arquitectura.md) |
| **Contrato** | [`04-contrato-api.md` §4.4 y §13.10](../04-contrato-api.md) |

## Lo vigente desde D66 (resumen)

La revisión del equipo del 2026-11-19 señaló (ARQ-R-1) que la llamada del portal al científico rompía el aislamiento de ASR1 y ASR2: el tráfico público llegaba hasta la base de escritura. D66 lo corrigió con la alternativa que este ADR había descartado:

- Al marcar una foto como pública, el científico genera **una sola vez** la miniatura (JPEG de 1200 px como máximo, sin metadatos) y publica `evidencia.publicada`. Al desmarcarla publica `evidencia.retirada`.
- consulta-publica guarda la miniatura en `foto_publica` de su propia base y la sirve desde ahí (`/api/publica/especies/fotos/{fotoId}`).
- Desaparecen la ruta sin rol del científico (`GET /api/cientifico/publico/especies/foto`) y `FotosCientificoHttp`.

D66 revisó el motivo de este ADR para descartar el evento: con la miniatura ya reducida, el mensaje pesa cientos de KB, no MB, y va por una cola aparte (`consulta-publica.fotos`).

Las secciones siguientes se conservan **como historia de la decisión**: explican por qué se eligió primero la llamada síncrona. Lo que describen sobre la ruta, la caché en memoria y `FotosCientificoHttp` ya no existe en el código. Los detalles vigentes están en D66 y en el contrato (04 §4.4, §7.6 y §13.10).

## Contexto (2026-11-19)

El portal público mostraba una tarjeta por registro con una ilustración genérica. En la revisión del front se pidió un catálogo por especie con su foto, para que la página no se viera vacía y para que el visitante reconozca la especie sin saberse el nombre científico.

Las fotos de los hallazgos están en el lado de escritura (`servicio-cientifico`, tabla `hallazgo_evidencia` y archivos en disco), junto a las coordenadas cifradas. El portal vive en el lado de lectura (`servicio-consulta-publica`, base `consulta_publica`), que se alimenta por eventos de RabbitMQ (CQRS, D6) y no tiene acceso a esos archivos.

Hay dos riesgos que pesan más que la estética:

1. **La ubicación.** Una foto puede mostrar el lugar (un letrero, un río reconocible) o traerlo en sus metadatos. Una foto interna no debe llegar al portal sin que alguien lo decida.
2. **El acoplamiento.** El portal es el componente con más tráfico (ASR1, ASR4) y no debe caerse si el lado de escritura está lento o abajo.

## Decisión original (2026-11-19; los puntos 2 a 5 los reemplazó D66)

1. **Ninguna foto es pública por defecto.** El investigador marca «Mostrar en el portal» en el detalle del hallazgo (`PUT /api/cientifico/hallazgos/{id}/evidencias/{evidenciaId}/publica`, columna `hallazgo_evidencia.publica`). Solo se puede marcar una fotografía; un audio nunca es público. Queda en el log quién lo hizo.
2. **El Servicio Científico entrega una versión reducida.** `GET /api/cientifico/publico/especies/foto?nombre=` devuelve la foto pública más reciente de la especie, reducida a 1200 px y recodificada en JPEG. Al recodificarla no sobrevive ningún metadato, aunque el original ya estaba limpio desde la subida (RNF9).
3. **consulta-publica la pide por HTTP interno y la guarda en una caché en memoria** (`FotosEspeciesService`):
   - Hasta 120 especies, con LRU.
   - 10 minutos para una foto y 2 minutos para "no tiene foto", así una especie sin foto no genera una petición interna por visita.
   - Tiempos máximos de 2 s para conectar y 5 s para leer.
   - Al navegador le responde con `Cache-Control: public` de 10 o 2 minutos.
4. **Si el científico no responde, el portal sigue funcionando:** la tarjeta muestra la ilustración y nada más falla. La foto es decoración, no dato: el catálogo, las fichas y la búsqueda salen de `consulta_publica` como siempre.
5. **La ruta de la foto es la única del científico sin rol.** Está declarada como excepción explícita en `TodosLosEndpointsExigenRolTest` y `ArquitecturaSeguridadTest`. Desde afuera no se alcanza: el gateway exige rol interno para todo `/api/cientifico/**`. Solo responde a quien está en la red interna, y solo con fotos que un investigador ya decidió publicar.

## Alternativas consideradas

| Alternativa | Por qué no se eligió |
|---|---|
| **Copiar una miniatura al lado de lectura cuando se marca la foto** (por evento de RabbitMQ y una tabla `foto_especie` en `consulta_publica`). Es lo que propuso la revisión | Es la más pura en CQRS, pero cuesta más de lo que resuelve hoy. Los mensajes llevarían imágenes de cientos de KB por la cola que hoy solo lleva eventos pequeños. Habría otra tabla que sincronizar, con su propio caso de error: marcar o desmarcar, reintentos, eventos perdidos (lo que ya reporta Hernando con el outbox). Y desmarcar una foto debe retirarla del portal enseguida, lo que con copia exige un segundo evento. Con la caché, el tráfico del portal casi nunca llega al científico, y si el científico se cae solo se pierde la foto |
| **Publicar siempre la última foto del hallazgo** | Una foto interna podría mostrar el lugar exacto sin que nadie lo decida |
| **Que consulta-publica use una cuenta de servicio de Keycloak** para leer la foto con token | Otro cliente y otro secreto que rotar solo para leer datos que ya son públicos |
| **Fotos de internet cargadas por el navegador** (Wikimedia, iNaturalist) | Dependencia externa sin control, la IP del visitante saldría a un tercero y la CSP del portal tendría que abrirse |

## Consecuencias

**A favor**
- Ninguna foto sale sin decisión humana, y lo que sale está reducido y sin metadatos.
- El portal no depende del científico para funcionar: si el científico está caído, el portal se ve igual, solo que sin fotos.
- No se agrega nada a RabbitMQ ni una tabla nueva al lado de lectura.

**En contra (aceptado)**
- Es una llamada síncrona del lado de lectura al de escritura. Está acotada: solo la foto, con caché, con tiempos máximos y con respuesta de reemplazo. No se usa para ningún dato del catálogo.
- Con varias instancias de consulta-publica, cada una tiene su propia caché. Con el tráfico esperado de la Entrega 3 alcanza; si no, la caché pasa a Redis, que ya está en la infraestructura.
- Quien publica una foto debe revisar que no muestre el lugar. Esto se le recuerda en la casilla del detalle.
- Al desplegar en contenedores, `SERVICIO_CIENTIFICO_URL` de consulta-publica debe apuntar al científico por la red interna. El puerto del científico no se publica afuera.

## Cuándo revisarla (lo que se escribió, y lo que pasó)

Lo que pasó: se revisó antes de medir. La revisión de arquitectura del equipo consideró que el aislamiento del lado público (ASR1, ASR2) vale más que ahorrarse la cola, y D66 la reemplazó el 2026-11-21. Lo que se había escrito:

Pasamos a la copia por evento (la alternativa de la revisión) si se cumple alguna de estas condiciones:

- las mediciones de ASR1 o ASR4 con k6 muestran que la foto pesa en la latencia del portal;
- el científico pasa a tener ventanas de mantenimiento largas;
- se publican fotos de más especies de las que caben en la caché.

La medición con k6 de la portada nueva de especies ya está en el plan de Juanita.

## Pruebas que la sostienen hoy

- `FotosPublicasEventosMySqlTest` (científico): miniatura al marcar, eventos `evidencia.*`, y la ruta sin rol ya no existe.
- `ConsumidorFotosIntegracionTest` y `EspeciesPublicasIntegracionTest` (consulta-publica): la foto llega por la cola y se sirve desde la base de lectura.
- `LimpiadorMetadatosTest` y `LimpiadorAudioTest` (científico): lo que se guarda, y por lo tanto la miniatura, no trae ubicación en los metadatos.
