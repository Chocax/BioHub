package co.edu.unbosque.biohub.gateway.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Proxy de teselas del mapa (prefijo biohub.gateway.teselas, SEC-J-04).
 *
 * @param urlOrigen            plantilla del servidor de teselas, con {z}, {x} e {y}
 * @param agenteUsuario        User-Agent que identifica a BioHub ante el servidor (lo exige la política de OSM)
 * @param zoomMaximo           zoom más alto que se sirve; igual al ZOOM_MAXIMO del mapa de web-interna
 * @param porUsuario           teselas por ventana y por usuario (evita usar el gateway para descargar el mapa entero)
 * @param cacheMaximo          memoria máxima de la caché de teselas
 * @param vidaCache            cuánto se guarda una tesela en la caché del gateway
 * @param vidaNavegador        max-age que recibe el navegador (privado: nunca en cachés compartidas)
 * @param descargasSimultaneas conexiones simultáneas al servidor de origen (la política de OSM pide 2 como máximo)
 * @param tamanoMaximo         tamaño máximo de una tesela; si el origen manda más, se rechaza
 * @param timeoutConexion      tiempo máximo para conectar con el origen
 * @param timeoutLectura       tiempo máximo para recibir la tesela
 */
@ConfigurationProperties(prefix = "biohub.gateway.teselas")
public record PropiedadesTeselas(String urlOrigen, String agenteUsuario, int zoomMaximo, int porUsuario,
                                 DataSize cacheMaximo, Duration vidaCache, Duration vidaNavegador,
                                 int descargasSimultaneas, DataSize tamanoMaximo, Duration timeoutConexion,
                                 Duration timeoutLectura) {

    public PropiedadesTeselas {
        urlOrigen = urlOrigen == null || urlOrigen.isBlank()
                ? "https://tile.openstreetmap.org/{z}/{x}/{y}.png" : urlOrigen;
        agenteUsuario = agenteUsuario == null || agenteUsuario.isBlank()
                ? "BioHub-api-gateway/0.1 (+https://github.com/JuaniMP/BioHub)" : agenteUsuario;
        zoomMaximo = zoomMaximo > 0 ? zoomMaximo : 12;
        porUsuario = porUsuario > 0 ? porUsuario : 600;
        cacheMaximo = cacheMaximo == null ? DataSize.ofMegabytes(64) : cacheMaximo;
        vidaCache = vidaCache == null ? Duration.ofDays(7) : vidaCache;
        vidaNavegador = vidaNavegador == null ? Duration.ofDays(1) : vidaNavegador;
        descargasSimultaneas = descargasSimultaneas > 0 ? descargasSimultaneas : 2;
        tamanoMaximo = tamanoMaximo == null ? DataSize.ofKilobytes(512) : tamanoMaximo;
        timeoutConexion = timeoutConexion == null ? Duration.ofSeconds(3) : timeoutConexion;
        timeoutLectura = timeoutLectura == null ? Duration.ofSeconds(10) : timeoutLectura;
    }
}
