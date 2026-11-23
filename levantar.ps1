<#
.SYNOPSIS
  Levanta BioHub completo en tu PC: Docker (MySQL, Redis, RabbitMQ, Keycloak), los 5 servicios Spring Boot
  y las dos webs. Va diciendo qué arranca, en qué puerto y si quedó bien.

.DESCRIPTION
  Pasos: revisa herramientas y el .env, prende Docker Desktop si está apagado, sube los contenedores y espera
  a que estén sanos, compila los servicios si cambió algo, los arranca en segundo plano (sobreviven a cerrar
  la terminal), arranca las dos webs y al final muestra la tabla de puertos y los usuarios de prueba.
  Los logs quedan en .biohub-local\logs (no se suben al repo).

.PARAMETER Perfil
  local (por defecto): todas las bases en Docker; no toca Aiven.
  aiven: Redis, RabbitMQ y Keycloak en Docker y las bases en Aiven (necesita las variables de 09-despliegue §4).

.PARAMETER Sembrar
  Al final crea datos de ejemplo (hallazgos, equipos, un consumidor y un reporte). Solo con -Perfil local.

.PARAMETER Compilar
  Compila los servicios aunque no haya cambios (por defecto solo compila si algo cambió).

.PARAMETER ReimportarRealm
  Borra los datos de Keycloak de desarrollo y vuelve a importar infra\keycloak\realm-biohub.json.
  Úsalo si los usuarios de prueba no entran o si cambió el realm.

.PARAMETER Estado
  Solo muestra qué está arriba, sin arrancar nada.

.PARAMETER Detener
  Apaga los servicios y las webs y detiene los contenedores (los datos se conservan).

.PARAMETER ArchivoEnv
  Archivo de variables a usar (por defecto .env de la raíz).

.EXAMPLE
  .\levantar.ps1
.EXAMPLE
  .\levantar.ps1 -Sembrar
.EXAMPLE
  .\levantar.ps1 -Detener
#>
[CmdletBinding()]
param(
  [ValidateSet('local', 'aiven')][string]$Perfil = 'local',
  [switch]$Sembrar,
  [switch]$Compilar,
  [switch]$ReimportarRealm,
  [switch]$Estado,
  [switch]$Detener,
  [string]$ArchivoEnv = '.env'
)

$ErrorActionPreference = 'Continue'
# Lanzado desde otra consola (Git Bash, un IDE) a veces no trae ComSpec, y con eso se corren los comandos.
if (-not $env:ComSpec) { $env:ComSpec = Join-Path $env:SystemRoot 'System32\cmd.exe' }
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$Raiz = $PSScriptRoot
$Carpeta = Join-Path $Raiz '.biohub-local'
$Logs = Join-Path $Carpeta 'logs'
$ArchivoEstado = Join-Path $Carpeta 'estado.json'
$Inicio = Get-Date

# ----------------------------------------------------------------------------------------------- utilidades
function Hora { (Get-Date).ToString('HH:mm:ss') }
function Titulo($texto) { Write-Host ''; Write-Host "[$(Hora)] $texto" -ForegroundColor Cyan }
function Bien($texto) { Write-Host '   OK    ' -ForegroundColor Green -NoNewline; Write-Host $texto }
function Mal($texto) { Write-Host '   FALLA ' -ForegroundColor Red -NoNewline; Write-Host $texto }
function Aviso($texto) { Write-Host '   OJO   ' -ForegroundColor Yellow -NoNewline; Write-Host $texto }
function Nota($texto) { Write-Host "         $texto" -ForegroundColor DarkGray }
function Salir($texto) { Write-Host ''; Mal $texto; Write-Host ''; exit 1 }

function Leer-Env($ruta) {
  $vars = [ordered]@{}
  if (-not (Test-Path $ruta)) { return $vars }
  foreach ($linea in [IO.File]::ReadAllLines($ruta)) {
    $l = $linea.Trim()
    if ($l -eq '' -or $l.StartsWith('#') -or -not $l.Contains('=')) { continue }
    $i = $l.IndexOf('=')
    $clave = $l.Substring(0, $i).Trim()
    $valor = $l.Substring($i + 1).Trim()
    if ($valor.Length -ge 2 -and (($valor.StartsWith('"') -and $valor.EndsWith('"')) -or ($valor.StartsWith("'") -and $valor.EndsWith("'")))) {
      $valor = $valor.Substring(1, $valor.Length - 2)
    }
    $vars[$clave] = $valor
  }
  return $vars
}

function Valor($clave, $defecto) {
  if ($script:Vars.Contains($clave) -and $script:Vars[$clave] -ne '') { return $script:Vars[$clave] }
  return $defecto
}

function Puerto-Abierto([int]$puerto) {
  $cliente = New-Object Net.Sockets.TcpClient
  try {
    $intento = $cliente.BeginConnect('127.0.0.1', $puerto, $null, $null)
    if (-not $intento.AsyncWaitHandle.WaitOne(400)) { return $false }
    $cliente.EndConnect($intento)
    return $true
  } catch { return $false } finally { $cliente.Close() }
}

function Responde($url, $debeContener) {
  try {
    $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 4
    # PowerShell 5.1 entrega como bytes las respuestas que no son text/* (el health de Spring es application/vnd...+json).
    $texto = $r.Content
    if ($texto -is [byte[]]) { $texto = [Text.Encoding]::UTF8.GetString($texto) }
    if ($debeContener) { return $texto -match $debeContener }
    return $true
  } catch { return $false }
}

# Corre un comando de consola y deja su salida en un log; devuelve el código de salida.
function Correr($comando, $log, $dir = $Raiz) {
  # Start-Process pasa los argumentos tal cual (el & de PowerShell 5.1 estropea las comillas internas).
  $p = Start-Process -FilePath $env:ComSpec -ArgumentList "/d /s /c `"$comando > `"$log`" 2>&1`"" -WorkingDirectory $dir -NoNewWindow -Wait -PassThru
  return $p.ExitCode
}

function Ultimas-Lineas($log, $n = 25) {
  if (Test-Path $log) { Get-Content $log -Tail $n | ForEach-Object { Nota $_ } }
}

# Espera con un contador en la misma línea.
# (Nombres con __ para no tapar las variables que usa el bloque $__listo: PowerShell las busca por alcance dinámico.)
# Devuelve los segundos que tardó, -1 si se acabó el tiempo o -2 si el proceso $__pid murió antes.
function Esperar($__texto, [scriptblock]$__listo, [int]$__limite, $__pid = $null) {
  $__t0 = Get-Date
  while ($true) {
    if (& $__listo) { Write-Host "`r$(' ' * 70)`r" -NoNewline; return [int]((Get-Date) - $__t0).TotalSeconds }
    if ($__pid -and -not (Get-Process -Id $__pid -ErrorAction SilentlyContinue)) { Write-Host "`r$(' ' * 70)`r" -NoNewline; return -2 }
    $__seg = [int]((Get-Date) - $__t0).TotalSeconds
    if ($__seg -ge $__limite) { Write-Host "`r$(' ' * 70)`r" -NoNewline; return -1 }
    Write-Host "`r         esperando $__texto... $__seg s" -NoNewline -ForegroundColor DarkGray
    Start-Sleep -Milliseconds 1500
  }
}

# ------------------------------------------------------------------------------------- qué hay que levantar
$Servicios = @(
  @{ Nombre = 'servicio-cientifico';       Clave = 'SERVICIO_CIENTIFICO_PORT';       Defecto = 8081; Texto = 'Servicio científico' },
  @{ Nombre = 'servicio-equipos';          Clave = 'SERVICIO_EQUIPOS_PORT';          Defecto = 8082; Texto = 'Servicio de equipos' },
  @{ Nombre = 'servicio-consulta-publica'; Clave = 'SERVICIO_CONSULTA_PUBLICA_PORT'; Defecto = 8083; Texto = 'Servicio de consulta pública' },
  @{ Nombre = 'servicio-reportes';         Clave = 'SERVICIO_REPORTES_PORT';         Defecto = 8084; Texto = 'Servicio de reportes' },
  @{ Nombre = 'api-gateway';               Clave = 'API_GATEWAY_PORT';               Defecto = 8080; Texto = 'API Gateway' }
)
$Webs = @(
  @{ Nombre = 'web-interna'; Puerto = 5173; Texto = 'Web interna' },
  @{ Nombre = 'web-publica'; Puerto = 5174; Texto = 'Web pública' }
)
$Contenedores = @(
  @{ Servicio = 'mysql-transaccional'; Clave = 'MYSQL_TRANSACCIONAL_PORT'; Defecto = 3307; Texto = 'MySQL transaccional (científico y equipos)'; Solo = 'local' },
  @{ Servicio = 'mysql-lectura';       Clave = 'MYSQL_LECTURA_PORT';       Defecto = 3308; Texto = 'MySQL de lectura (portal público)';        Solo = 'local' },
  @{ Servicio = 'mysql-analitica';     Clave = 'MYSQL_ANALITICA_PORT';     Defecto = 3309; Texto = 'MySQL analítica (reportes)';               Solo = 'local' },
  @{ Servicio = 'mysql-gateway';       Clave = 'MYSQL_GATEWAY_PORT';       Defecto = 3310; Texto = 'MySQL del gateway (API keys)';             Solo = 'local' },
  @{ Servicio = 'redis';               Clave = 'REDIS_PORT';               Defecto = 6379; Texto = 'Redis (caché y límites)';                  Solo = $null },
  @{ Servicio = 'rabbitmq';            Clave = 'RABBITMQ_PORT';            Defecto = 5672; Texto = 'RabbitMQ (eventos)';                       Solo = $null },
  @{ Servicio = 'keycloak';            Clave = 'KEYCLOAK_PORT';            Defecto = 8180; Texto = 'Keycloak (inicio de sesión)';              Solo = $null }
)
$Usuarios = @(
  @('investigador1', 'Investigador de campo',    'registra hallazgos con fotos y audios, edita fichas, reclasifica'),
  @('cientifico1',   'Científico de alto rango', 'todo lo anterior + mapa y coordenadas exactas'),
  @('auditor1',      'Auditor científico',       'consulta, historial y auditoría (solo lectura)'),
  @('analista1',     'Analista ambiental',       'búsqueda y reportes de presión'),
  @('admin1',        'Administrador',            'equipos, usuarios y roles, API keys, reportes'),
  @('sinrol1',       'sin rol',                  'para comprobar que sin rol no se entra')
)

# ------------------------------------------------------------------------------------------- docker compose
function Nombre-Contenedor($servicio) { "$script:Proyecto-$servicio" }

function Compose([string]$argumentos, $log) {
  $archivos = "-f `"$(Join-Path $Raiz 'docker-compose.yml')`""
  if ($script:Override) { $archivos += " -f `"$script:Override`"" }
  return Correr "docker compose -p $script:Proyecto --env-file `"$script:RutaEnv`" $archivos $argumentos" $log
}

# docker-compose.yml fija los nombres "biohub-*". Con otro COMPOSE_PROJECT_NAME (un segundo entorno) se les
# cambia el nombre con un archivo extra, para que no choquen con los del entorno normal.
function Preparar-Override {
  $script:Override = $null
  if ($script:Proyecto -eq 'biohub') { return }
  $lineas = @('services:')
  foreach ($c in @('mysql-transaccional', 'mysql-lectura', 'mysql-analitica', 'mysql-gateway', 'redis', 'rabbitmq', 'rabbitmq-usuarios', 'keycloak')) {
    $lineas += "  ${c}:"
    $lineas += "    container_name: $script:Proyecto-$c"
  }
  $script:Override = Join-Path $Carpeta "compose-$script:Proyecto.yml"
  [IO.File]::WriteAllLines($script:Override, $lineas)
}

# Corre infra/mysql/initdb/01-usuarios.sh dentro del contenedor (es repetible): usuarios *_app y *_migr con sus
# permisos y, en la transaccional, el bloqueo del historial si las tablas ya existen.
function Permisos-MySQL($servicio) {
  $log = Join-Path $Logs "mysql-usuarios-$servicio.log"
  $codigo = Correr "docker exec $(Nombre-Contenedor $servicio) bash /docker-entrypoint-initdb.d/01-usuarios.sh" $log
  $lineas = @(Get-Content $log -ErrorAction SilentlyContinue | Where-Object { $_ -match '\[biohub\]|ERROR' } |
    ForEach-Object { $_ -replace '^\[biohub\]\s*', '' })
  [pscustomobject]@{ Ok = ($codigo -eq 0); Lineas = $lineas; Historial = ($lineas | Where-Object { $_ -like 'historial*' } | Select-Object -First 1) }
}

function Docker-Arriba {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Salir 'No encuentro Docker. Instala Docker Desktop: https://www.docker.com/products/docker-desktop/' }
  & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { return }
  $exe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
  if (-not (Test-Path $exe)) { Salir 'Docker no está corriendo. Abre Docker Desktop y vuelve a intentar.' }
  Nota 'Docker Desktop estaba apagado: lo prendo'
  Start-Process $exe | Out-Null
  $s = Esperar 'Docker Desktop' { & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null; $LASTEXITCODE -eq 0 } 180
  if ($s -lt 0) { Salir 'Docker Desktop no arrancó en 3 minutos. Ábrelo a mano y vuelve a correr el script.' }
  Bien "Docker Desktop arriba ($s s)"
}

function Estado-Contenedor($nombre) {
  $salida = & docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.State.ExitCode}}' $nombre 2>$null
  if ($LASTEXITCODE -ne 0 -or -not $salida) { return @{ Estado = 'no existe'; Salud = ''; Codigo = '' } }
  $p = "$salida".Split('|')
  return @{ Estado = $p[0]; Salud = $p[1]; Codigo = $p[2] }
}

# ------------------------------------------------------------------------------------------ procesos propios
function Leer-Estado {
  if (Test-Path $ArchivoEstado) { try { return Get-Content $ArchivoEstado -Raw | ConvertFrom-Json } catch { } }
  return $null
}

function Iniciar($nombre, $dir, $comando) {
  $log = Join-Path $Logs "$nombre.log"
  if (Test-Path $log) { Move-Item $log "$log.anterior" -Force }
  $p = Start-Process -FilePath $env:ComSpec -ArgumentList "/d /s /c `"$comando > `"$log`" 2>&1`"" -WorkingDirectory $dir -WindowStyle Hidden -PassThru
  return [pscustomobject]@{ nombre = $nombre; pid = $p.Id; log = $log }
}

function Apagar-Procesos {
  $estado = Leer-Estado
  $apagados = 0
  if ($estado -and $estado.procesos) {
    foreach ($p in $estado.procesos) {
      if (Get-Process -Id $p.pid -ErrorAction SilentlyContinue) {
        & taskkill /PID $p.pid /T /F 2>$null | Out-Null
        $apagados += 1
      }
    }
  }
  # Por si quedó algo de una corrida anterior escuchando en los puertos de BioHub (solo java y node).
  $puertos = @($Servicios | ForEach-Object { [int](Valor $_.Clave $_.Defecto) }) + @($Webs | ForEach-Object { $_.Puerto })
  foreach ($puerto in $puertos) {
    $conexiones = Get-NetTCPConnection -LocalPort $puerto -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conexiones) {
      $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
      if ($proc -and @('java', 'node') -contains $proc.ProcessName) {
        & taskkill /PID $proc.Id /T /F 2>$null | Out-Null
        $apagados += 1
      }
    }
  }
  return $apagados
}

# --------------------------------------------------------------------------------------------- tabla final
function Mostrar-Tabla {
  Titulo 'Así quedó BioHub'
  $filas = @()
  foreach ($w in $Webs) {
    $ok = Responde "http://localhost:$($w.Puerto)/" $null
    $filas += , @($w.Texto, $w.Puerto, $ok, "http://localhost:$($w.Puerto)")
  }
  foreach ($s in ($Servicios | Sort-Object { [int](Valor $_.Clave $_.Defecto) })) {
    $p = [int](Valor $s.Clave $s.Defecto)
    $ok = Responde "http://127.0.0.1:$p/actuator/health" '"UP"'
    $filas += , @($s.Texto, $p, $ok, "http://localhost:$p")
  }
  foreach ($c in $Contenedores) {
    if ($c.Solo -and $c.Solo -ne $script:PerfilUsado) { continue }
    $e = Estado-Contenedor (Nombre-Contenedor $c.Servicio)
    $ok = ($e.Estado -eq 'running' -and ($e.Salud -eq '' -or $e.Salud -eq 'healthy'))
    $extra = "contenedor $(Nombre-Contenedor $c.Servicio)"
    if ($c.Servicio -eq 'keycloak') { $extra = "http://localhost:$(Valor $c.Clave $c.Defecto)/admin" }
    if ($c.Servicio -eq 'rabbitmq') { $extra = "panel http://localhost:$(Valor 'RABBITMQ_MANAGEMENT_PORT' 15672)" }
    $filas += , @($c.Texto, (Valor $c.Clave $c.Defecto), $ok, $extra)
  }
  Write-Host ''
  Write-Host ('   {0,-44} {1,-7} {2,-8} {3}' -f 'Componente', 'Puerto', 'Estado', 'Dirección') -ForegroundColor White
  Write-Host ('   ' + ('-' * 100)) -ForegroundColor DarkGray
  $caidos = 0
  foreach ($f in $filas) {
    Write-Host ('   {0,-44} {1,-7} ' -f $f[0], $f[1]) -NoNewline
    if ($f[2]) { Write-Host ('{0,-8} ' -f 'arriba') -ForegroundColor Green -NoNewline } else { Write-Host ('{0,-8} ' -f 'caído') -ForegroundColor Red -NoNewline; $caidos += 1 }
    Write-Host $f[3]
  }
  return $caidos
}

function Mostrar-Usuarios {
  Titulo 'Usuarios de prueba (realm biohub) para la web interna'
  $clave = Valor 'KEYCLOAK_TEST_USERS_PASSWORD' ''
  Write-Host '   Contraseña de todos: ' -NoNewline
  if ($clave) { Write-Host $clave -ForegroundColor Yellow } else { Write-Host '(la de KEYCLOAK_TEST_USERS_PASSWORD en tu .env)' -ForegroundColor Yellow }
  Write-Host ''
  foreach ($u in $Usuarios) { Write-Host ('   {0,-15} {1,-26} {2}' -f $u[0], $u[1], $u[2]) }
  Nota ''
  Nota "Consola de Keycloak: http://localhost:$(Valor 'KEYCLOAK_PORT' 8180)/admin con KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD del .env."
  Nota 'Más usuarios: entra como admin1 a "Usuarios" en la web interna y créalos ahí.'
}

# =========================================================================================== programa
Set-Location $Raiz
New-Item -ItemType Directory -Force -Path $Logs | Out-Null
$script:RutaEnv = if ([IO.Path]::IsPathRooted($ArchivoEnv)) { $ArchivoEnv } else { Join-Path $Raiz $ArchivoEnv }
$script:Vars = Leer-Env $script:RutaEnv
$script:Proyecto = Valor 'COMPOSE_PROJECT_NAME' 'biohub'
$script:PerfilUsado = $Perfil
$anterior = Leer-Estado
if (($Estado -or $Detener) -and $anterior -and $anterior.perfil) { $script:PerfilUsado = $anterior.perfil }
Preparar-Override

Write-Host ''
Write-Host '   BioHub · entorno local' -ForegroundColor Green
Nota "raíz: $Raiz · variables: $script:RutaEnv · proyecto Docker: $script:Proyecto"

if ($Estado) {
  Docker-Arriba
  $null = Mostrar-Tabla
  Mostrar-Usuarios
  Write-Host ''
  exit 0
}

if ($Detener) {
  Titulo 'Apagando servicios y webs'
  $n = Apagar-Procesos
  Bien "procesos detenidos: $n"
  if (& docker info --format '{{.ServerVersion}}' 2>$null) {
    Titulo 'Deteniendo contenedores (los datos se conservan)'
    $codigo = Compose 'stop' (Join-Path $Logs 'docker-stop.log')
    if ($codigo -eq 0) { Bien 'contenedores detenidos' } else { Mal 'docker compose stop falló'; Ultimas-Lineas (Join-Path $Logs 'docker-stop.log') }
  }
  if (Test-Path $ArchivoEstado) { Remove-Item $ArchivoEstado -Force }
  Write-Host ''
  Nota 'Para borrar también los datos de Docker: docker compose down -v'
  Write-Host ''
  exit 0
}

# ------------------------------------------------------------------------------------- 1. herramientas
Titulo '1/7 Revisando herramientas'
$javaVersion = (& $env:ComSpec /d /c 'java -version 2>&1' | Select-Object -First 1)
if (-not $javaVersion) { Salir 'No encuentro Java. Instala el JDK 21 (Temurin) y ábrelo en una terminal nueva.' }
if ("$javaVersion" -match 'version "(\d+)') {
  if ([int]$Matches[1] -lt 21) { Salir "Java $($Matches[1]) es muy viejo: BioHub necesita Java 21. Si ya lo instalaste, cierra y abre la terminal." }
  Bien "Java $($Matches[1])"
}
if (Get-Command mvn -ErrorAction SilentlyContinue) { Bien 'Maven' } else { Salir 'No encuentro Maven (mvn). Instálalo y agrégalo al Path.' }
if (Get-Command node -ErrorAction SilentlyContinue) { Bien "Node $(& node -v)" } else { Salir 'No encuentro Node.js (22 o más nuevo).' }
Docker-Arriba
Bien "Docker $(& docker info --format '{{.ServerVersion}}' 2>$null)"

# ------------------------------------------------------------------------------------- 2. variables
Titulo "2/7 Revisando variables ($([IO.Path]::GetFileName($script:RutaEnv)), perfil $Perfil)"
if (-not (Test-Path $script:RutaEnv)) {
  Salir "No existe $script:RutaEnv. Cópialo del ejemplo:  Copy-Item .env.example .env  y cambia las contraseñas."
}
$obligatorias = @('REDIS_PASSWORD', 'RABBITMQ_USER', 'RABBITMQ_PASSWORD', 'KEYCLOAK_ADMIN_USER', 'KEYCLOAK_ADMIN_PASSWORD',
  'KEYCLOAK_TEST_USERS_PASSWORD')
if ($Perfil -eq 'local') {
  $obligatorias += @('MYSQL_TRANSACCIONAL_ROOT_PASSWORD', 'MYSQL_CIENTIFICO_USER', 'MYSQL_CIENTIFICO_PASSWORD', 'MYSQL_EQUIPOS_USER',
    'MYSQL_EQUIPOS_PASSWORD', 'MYSQL_LECTURA_ROOT_PASSWORD', 'MYSQL_LECTURA_USER', 'MYSQL_LECTURA_PASSWORD',
    'MYSQL_ANALITICA_ROOT_PASSWORD', 'MYSQL_ANALITICA_USER', 'MYSQL_ANALITICA_PASSWORD', 'MYSQL_GATEWAY_ROOT_PASSWORD',
    'MYSQL_GATEWAY_USER', 'MYSQL_GATEWAY_PASSWORD')
}
$faltan = @($obligatorias | Where-Object { -not (Valor $_ '') })
if ($faltan.Count -gt 0) {
  Mal 'Faltan estas variables (cópialas de .env.example y ponles valor):'
  $faltan | ForEach-Object { Nota "- $_" }
  Salir 'Completa el .env y vuelve a correr el script.'
}
Bien "$($obligatorias.Count) variables obligatorias presentes"
$sinCambiar = @($script:Vars.Keys | Where-Object { "$($script:Vars[$_])" -like 'cambiar_*' })
if ($sinCambiar.Count -gt 0) { Aviso "siguen con el valor de ejemplo: $($sinCambiar -join ', ')" }
if ((Valor 'KEYCLOAK_TEST_USERS_PASSWORD' '').Length -lt 12) { Aviso 'KEYCLOAK_TEST_USERS_PASSWORD tiene menos de 12 caracteres: la política del realm la rechaza' }

# Llave de cifrado de coordenadas: en local se genera una si no existe (en Aiven debe ser la del equipo).
$enCientifico = Leer-Env (Join-Path $Raiz 'servicio-cientifico\.env')
if (-not (Valor 'UBICACION_CLAVES' '') -and -not $enCientifico.Contains('UBICACION_CLAVES')) {
  if ($Perfil -eq 'aiven') { Salir 'Falta UBICACION_CLAVES: con Aiven debe ser la misma llave del equipo (pídesela a quien cifró los datos).' }
  $bytes = New-Object byte[] 32
  (New-Object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($bytes)
  $texto = [IO.File]::ReadAllText($script:RutaEnv)
  $nuevo = ''
  if ($texto.Length -gt 0 -and -not $texto.EndsWith("`n")) { $nuevo = "`r`n" }
  $nuevo += "`r`n# Llave para cifrar coordenadas en desarrollo local (la generó levantar.ps1). No la cambies: los datos quedan cifrados con ella.`r`n"
  $nuevo += "UBICACION_CLAVES=v1:$([Convert]::ToBase64String($bytes))`r`nUBICACION_CLAVE_ACTIVA=v1`r`n"
  [IO.File]::AppendAllText($script:RutaEnv, $nuevo)
  $script:Vars = Leer-Env $script:RutaEnv
  Aviso 'no había UBICACION_CLAVES: generé una llave local y la agregué al final del .env'
} else {
  Bien 'llave de cifrado de coordenadas presente'
}
# Secreto del cliente biohub-gateway de Keycloak (el gateway administra usuarios con él, HU-10). Keycloak siempre corre
# en Docker, así que si falta se genera (solo letras y números) y más abajo se le pone el mismo al cliente.
if (-not (Valor 'KEYCLOAK_GATEWAY_CLIENT_SECRET' '')) {
  $bytes = New-Object byte[] 24
  (New-Object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($bytes)
  $texto = [IO.File]::ReadAllText($script:RutaEnv)
  $nuevo = ''
  if ($texto.Length -gt 0 -and -not $texto.EndsWith("`n")) { $nuevo = "`r`n" }
  $nuevo += "`r`n# Secreto del cliente biohub-gateway de Keycloak: lo generó levantar.ps1.`r`n"
  $nuevo += "KEYCLOAK_GATEWAY_CLIENT_SECRET=$(($bytes | ForEach-Object { $_.ToString('x2') }) -join '')`r`n"
  [IO.File]::AppendAllText($script:RutaEnv, $nuevo)
  $script:Vars = Leer-Env $script:RutaEnv
  Aviso 'no había KEYCLOAK_GATEWAY_CLIENT_SECRET: generé uno y lo agregué al final del .env'
}
# Usuarios de migración de las bases locales: uno por servicio, distinto del de la app (como en Aiven). Si faltan
# en el .env se generan contraseñas al azar y se agregan al final, igual que la llave de coordenadas.
$Migracion = [ordered]@{ CIENTIFICO = 'cientifico_migr'; EQUIPOS = 'equipos_migr'; CONSULTA = 'consulta_migr'; REPORTES = 'reportes_migr'; GATEWAY = 'gateway_migr' }
if ($Perfil -eq 'local') {
  $sinClave = @($Migracion.Keys | Where-Object { -not (Valor "${_}_MIGR_PASSWORD" '') })
  if ($sinClave.Count -gt 0) {
    $texto = [IO.File]::ReadAllText($script:RutaEnv)
    $nuevo = ''
    if ($texto.Length -gt 0 -and -not $texto.EndsWith("`n")) { $nuevo = "`r`n" }
    $nuevo += "`r`n# Usuarios de migraciones (Flyway) de las bases locales: los generó levantar.ps1.`r`n"
    foreach ($k in $sinClave) {
      $bytes = New-Object byte[] 18
      (New-Object Security.Cryptography.RNGCryptoServiceProvider).GetBytes($bytes)
      if (-not (Valor "${k}_MIGR_USER" '')) { $nuevo += "${k}_MIGR_USER=$($Migracion[$k])`r`n" }
      $nuevo += "${k}_MIGR_PASSWORD=$(($bytes | ForEach-Object { $_.ToString('x2') }) -join '')`r`n"
    }
    [IO.File]::AppendAllText($script:RutaEnv, $nuevo)
    $script:Vars = Leer-Env $script:RutaEnv
    Aviso "no había contraseña de migraciones para $($sinClave -join ', '): las generé y las agregué al final del .env"
  } else {
    Bien 'usuarios de migraciones de las bases locales presentes'
  }
}
if ((Valor 'BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO' (Valor 'RABBITMQ_USER' '')) -ne (Valor 'RABBITMQ_USER' '')) {
  Aviso 'BIOHUB_EVENTOS_PUBLICADOR_CIENTIFICO no es igual a RABBITMQ_USER: el portal público rechazará los eventos'
}

# Todo lo del archivo pasa al entorno de este proceso: lo heredan Docker, los servicios y las webs.
foreach ($k in $script:Vars.Keys) { Set-Item -Path "env:$k" -Value $script:Vars[$k] }
$kc = Valor 'KEYCLOAK_PORT' 8180
$gw = Valor 'API_GATEWAY_PORT' 8080
$derivadas = [ordered]@{
  SPRING_PROFILES_ACTIVE         = $Perfil
  KEYCLOAK_ISSUER_URI            = "http://localhost:$kc/realms/biohub"
  KEYCLOAK_ADMIN_URL             = "http://localhost:$kc"
  SERVICIO_CIENTIFICO_URL        = "http://localhost:$(Valor 'SERVICIO_CIENTIFICO_PORT' 8081)"
  SERVICIO_EQUIPOS_URL           = "http://localhost:$(Valor 'SERVICIO_EQUIPOS_PORT' 8082)"
  SERVICIO_CONSULTA_PUBLICA_URL  = "http://localhost:$(Valor 'SERVICIO_CONSULTA_PUBLICA_PORT' 8083)"
  SERVICIO_REPORTES_URL          = "http://localhost:$(Valor 'SERVICIO_REPORTES_PORT' 8084)"
  BIOHUB_GATEWAY_URL             = "http://localhost:$gw"
}
foreach ($k in $derivadas.Keys) {
  if ($k -eq 'SPRING_PROFILES_ACTIVE' -or -not $script:Vars.Contains($k)) { Set-Item -Path "env:$k" -Value $derivadas[$k] }
}
# En local las migraciones usan el usuario *_migr de cada base de Docker. Se fija aquí para que no gane un
# <SERVICIO>_MIGR_USER de Aiven puesto en el .env de un servicio (p. ej. api-gateway/.env).
if ($Perfil -eq 'local') {
  foreach ($k in $Migracion.Keys) {
    Set-Item -Path "env:${k}_MIGR_USER" -Value (Valor "${k}_MIGR_USER" $Migracion[$k])
    Set-Item -Path "env:${k}_MIGR_PASSWORD" -Value (Valor "${k}_MIGR_PASSWORD" '')
  }
}
$env:VITE_API_URL = "http://localhost:$gw"
$env:VITE_KEYCLOAK_URL = "http://localhost:$kc"

# ------------------------------------------------------------------------------------- 3. puertos libres
Titulo '3/7 Revisando que los puertos estén libres'
$previos = Apagar-Procesos
if ($previos -gt 0) { Nota "apagué $previos procesos de una corrida anterior"; Start-Sleep -Seconds 2 }
$ocupados = @()
foreach ($s in $Servicios) { $p = [int](Valor $s.Clave $s.Defecto); if (Puerto-Abierto $p) { $ocupados += "$p ($($s.Nombre))" } }
foreach ($w in $Webs) { if (Puerto-Abierto $w.Puerto) { $ocupados += "$($w.Puerto) ($($w.Nombre))" } }
if ($ocupados.Count -gt 0) {
  Mal "puertos ocupados por otro programa: $($ocupados -join ', ')"
  if ($previos -gt 0) {
    # Se intentó apagarlos y siguen ahí: casi siempre es una corrida anterior abierta como administrador, que una
    # consola normal no puede cerrar (Windows no lo permite).
    Nota 'Son de una corrida anterior que no pude cerrar. Si esa vez abriste la consola como administrador:'
    Nota 'abre CMD como administrador, ve a la carpeta del proyecto y corre  levantar -Detener  (luego  levantar).'
    Nota 'Si BioHub ya está funcionando, no hace falta reiniciar: revisa con  levantar -Estado'
    Salir 'No arranqué nada para no duplicar los servicios.'
  }
  Salir 'Ciérralo (o busca quién es con: Get-NetTCPConnection -LocalPort <puerto> -State Listen) y vuelve a correr.'
}
Bien 'puertos de servicios y webs libres'

# ------------------------------------------------------------------------------------- 4. Docker
Titulo "4/7 Contenedores ($(if ($Perfil -eq 'local') { 'bases en Docker' } else { 'bases en Aiven' }))"
$contenedoresPerfil = @($Contenedores | Where-Object { -not $_.Solo -or $_.Solo -eq $Perfil })
if ($ReimportarRealm) {
  Aviso 'reimportando el realm: se borran los datos de Keycloak de desarrollo'
  $null = Compose 'rm -sf keycloak' (Join-Path $Logs 'docker-keycloak-rm.log')
  & docker volume rm "$($script:Proyecto)_keycloak-data" 2>$null | Out-Null
}
$lista = ($contenedoresPerfil | ForEach-Object { $_.Servicio }) + @('rabbitmq-usuarios')
$logDocker = Join-Path $Logs 'docker-up.log'
Nota 'docker compose up -d (la primera vez descarga imágenes: puede tardar varios minutos)'
$codigo = Compose "up -d $($lista -join ' ')" $logDocker
if ($codigo -ne 0) { Mal 'docker compose up falló:'; Ultimas-Lineas $logDocker; Salir 'Revisa el mensaje de arriba (suele ser una variable que falta o un puerto ocupado).' }
foreach ($c in $contenedoresPerfil) {
  $contenedor = Nombre-Contenedor $c.Servicio
  $limite = 120; if ($c.Servicio -eq 'keycloak') { $limite = 240 }
  $s = Esperar $c.Texto { $e = Estado-Contenedor $contenedor; $e.Estado -eq 'running' -and ($e.Salud -eq '' -or $e.Salud -eq 'healthy') } $limite
  if ($s -ge 0) { Bien ("{0,-44} puerto {1,-6} ({2} s)" -f $c.Texto, (Valor $c.Clave $c.Defecto), $s) }
  else { Mal "$($c.Texto) no quedó sano. Mira: docker logs $contenedor"; Salir 'Sin la infraestructura los servicios no arrancan.' }
}
if ($Perfil -eq 'local') {
  # Se repite en cada arranque: una base creada antes (con el usuario de ALL PRIVILEGES) queda corregida sin
  # borrar datos, y si Flyway ya creó las tablas, el historial queda protegido desde antes de arrancar los servicios.
  $todoBien = $true
  foreach ($c in @($contenedoresPerfil | Where-Object { $_.Servicio -like 'mysql-*' })) {
    $r = Permisos-MySQL $c.Servicio
    if (-not $r.Ok) { $todoBien = $false; Mal "no se pudieron ajustar los usuarios de $($c.Servicio):"; $r.Lineas | ForEach-Object { Nota $_ } }
  }
  if ($todoBien) { Bien 'bases con usuario de datos y usuario de migraciones separados (sin ALL PRIVILEGES)' }
  else { Salir 'Revisa el mensaje de arriba (suele ser una contraseña con caracteres no permitidos en el .env).' }
}
$s = Esperar 'usuarios de RabbitMQ' { $e = Estado-Contenedor (Nombre-Contenedor 'rabbitmq-usuarios'); $e.Estado -eq 'exited' } 90
$e = Estado-Contenedor (Nombre-Contenedor 'rabbitmq-usuarios')
if ($s -ge 0 -and $e.Codigo -eq '0') { Bien 'usuarios de RabbitMQ por servicio creados' } else { Aviso "rabbitmq-usuarios terminó con código $($e.Codigo): docker logs $(Nombre-Contenedor 'rabbitmq-usuarios')" }

# ¿El realm tiene los usuarios de prueba con la contraseña del .env y el cliente del gateway con su secreto?
$urlToken = "http://localhost:$kc/realms/biohub/protocol/openid-connect/token"
$s = Esperar 'el realm biohub' { Responde "http://localhost:$kc/realms/biohub/.well-known/openid-configuration" 'issuer' } 120
try {
  $null = Invoke-RestMethod -Method Post -Uri $urlToken -Body @{ grant_type = 'password'; client_id = 'biohub-pruebas'; username = 'admin1'; password = (Valor 'KEYCLOAK_TEST_USERS_PASSWORD' '') }
  Bien 'usuarios de prueba entran con la contraseña del .env'
} catch {
  Aviso 'los usuarios de prueba NO entran con la contraseña del .env (el realm se importó con otra).'
  Nota 'Arréglalo con:  .\levantar.ps1 -ReimportarRealm'
}
function Gateway-Entra {
  try {
    $null = Invoke-RestMethod -Method Post -Uri $urlToken -Body @{ grant_type = 'client_credentials'; client_id = 'biohub-gateway'; client_secret = (Valor 'KEYCLOAK_GATEWAY_CLIENT_SECRET' '') }
    return $true
  } catch { return $false }
}
# Token de administración de Keycloak (realm master): lo usan el secreto del gateway y el tema del login.
$cabeceras = $null
try {
  $tokenAdmin = Invoke-RestMethod -Method Post -Uri "http://localhost:$kc/realms/master/protocol/openid-connect/token" -Body @{
    grant_type = 'password'; client_id = 'admin-cli'; username = (Valor 'KEYCLOAK_ADMIN_USER' ''); password = (Valor 'KEYCLOAK_ADMIN_PASSWORD' '') }
  $cabeceras = @{ Authorization = "Bearer $($tokenAdmin.access_token)" }
} catch { }
if (Gateway-Entra) {
  Bien 'cliente del gateway listo (administración de usuarios)'
} else {
  # El secreto solo se toma al importar el realm la primera vez. Si el .env tiene otro (o uno recién generado), se le
  # pone al cliente por la API de administración: así no hay que reimportar ni se pierden los usuarios creados a mano.
  $cliente = $null
  if ($cabeceras) {
    try { $cliente = @(Invoke-RestMethod -Uri "http://localhost:$kc/admin/realms/biohub/clients?clientId=biohub-gateway" -Headers $cabeceras)[0] } catch { }
  }
  if ($cliente -and $cliente.id) {
    try {
      $null = Invoke-RestMethod -Method Put -Uri "http://localhost:$kc/admin/realms/biohub/clients/$($cliente.id)" -Headers $cabeceras `
        -ContentType 'application/json' -Body (@{ clientId = 'biohub-gateway'; secret = (Valor 'KEYCLOAK_GATEWAY_CLIENT_SECRET' '') } | ConvertTo-Json)
    } catch { }
  }
  if (Gateway-Entra) {
    Bien 'cliente del gateway listo: le puse en Keycloak el secreto del .env'
  } elseif (-not $cliente) {
    Aviso 'tu Keycloak no tiene el cliente biohub-gateway (se importó antes de que existiera): la sala Usuarios fallará.'
    Nota 'Arréglalo con:  .\levantar.cmd -ReimportarRealm   (borra los usuarios que hayas creado a mano en Keycloak)'
  } else {
    Aviso 'el cliente biohub-gateway no acepta el secreto del .env: la sala Usuarios fallará.'
    Nota 'Arréglalo con:  .\levantar.cmd -ReimportarRealm'
  }
}
# Tema de inicio de sesión de BioHub (D64). Un realm importado antes de que existiera no lo tiene: se activa por la
# API de administración, sin tener que reimportar (y sin perder usuarios creados a mano).
try {
  if (-not $cabeceras) { throw 'sin token de administración' }
  $realm = Invoke-RestMethod -Uri "http://localhost:$kc/admin/realms/biohub" -Headers $cabeceras
  if ($realm.loginTheme -ne 'biohub') {
    $null = Invoke-RestMethod -Method Put -Uri "http://localhost:$kc/admin/realms/biohub" -Headers $cabeceras `
      -ContentType 'application/json' -Body (@{ loginTheme = 'biohub' } | ConvertTo-Json)
    Bien 'tema de inicio de sesión de BioHub activado'
  } else {
    Bien 'tema de inicio de sesión de BioHub'
  }
} catch {
  Aviso 'no se pudo activar el tema de inicio de sesión (no impide entrar): revisa KEYCLOAK_ADMIN_USER y KEYCLOAK_ADMIN_PASSWORD'
}

# ------------------------------------------------------------------------------------- 5. compilar
Titulo '5/7 Compilando los servicios'
$hayQueCompilar = [bool]$Compilar
foreach ($s in $Servicios) {
  $jar = Get-ChildItem (Join-Path $Raiz "$($s.Nombre)\target") -Filter '*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
  if (-not $jar) { $hayQueCompilar = $true; break }
  $fuentes = @(Get-ChildItem (Join-Path $Raiz "$($s.Nombre)\src\main") -Recurse -File -ErrorAction SilentlyContinue) +
    @((Get-Item (Join-Path $Raiz "$($s.Nombre)\pom.xml")), (Get-Item (Join-Path $Raiz 'pom.xml')))
  $ultimo = ($fuentes | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
  if ($ultimo -gt $jar.LastWriteTime) { $hayQueCompilar = $true; break }
}
if ($hayQueCompilar) {
  Nota 'mvn -DskipTests package (1 a 3 minutos; las pruebas se corren aparte con mvn test)'
  $t0 = Get-Date
  $logMvn = Join-Path $Logs 'compilacion.log'
  $codigo = Correr 'mvn -q -B -DskipTests package' $logMvn
  if ($codigo -ne 0) { Mal 'la compilación falló:'; Ultimas-Lineas $logMvn 40; Salir "Log completo: $logMvn" }
  Bien "compilado en $([int]((Get-Date) - $t0).TotalSeconds) s"
} else {
  Bien 'sin cambios desde la última compilación (usa -Compilar para forzarla)'
}

# ------------------------------------------------------------------------------------- 6. servicios
Titulo "6/7 Arrancando los servicios (perfil $Perfil)"
$procesos = @()
foreach ($s in $Servicios) {
  $dir = Join-Path $Raiz $s.Nombre
  $jar = Get-ChildItem (Join-Path $dir 'target') -Filter '*.jar' | Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
  # Se arranca desde la carpeta del servicio para que lea ../.env, como en el README.
  $procesos += Iniciar $s.Nombre $dir "java -Xms64m -Xmx512m -jar `"$($jar.FullName)`""
  Nota "$($s.Texto) arrancando en el puerto $(Valor $s.Clave $s.Defecto)"
}

foreach ($w in $Webs) {
  $dir = Join-Path $Raiz $w.Nombre
  $marca = Join-Path $dir 'node_modules\.package-lock.json'
  $lock = Join-Path $dir 'package-lock.json'
  if (-not (Test-Path $marca) -or (Get-Item $lock).LastWriteTime -gt (Get-Item $marca).LastWriteTime) {
    Nota "$($w.Texto): instalando dependencias (npm ci)"
    $codigo = Correr 'npm ci --no-audit --no-fund' (Join-Path $Logs "$($w.Nombre)-npm.log") $dir
    if ($codigo -ne 0) { Mal "npm ci falló en $($w.Nombre)"; Ultimas-Lineas (Join-Path $Logs "$($w.Nombre)-npm.log") }
  }
  $procesos += Iniciar $w.Nombre $dir 'npm run dev'
  Nota "$($w.Texto) arrancando en el puerto $($w.Puerto)"
}

$guardado = [pscustomobject]@{
  perfil   = $Perfil
  proyecto = $script:Proyecto
  env      = $script:RutaEnv
  inicio   = $Inicio.ToString('s')
  procesos = $procesos
}
$guardado | ConvertTo-Json -Depth 4 | Set-Content -Path $ArchivoEstado -Encoding UTF8

function Pid-De($nombre) { ($procesos | Where-Object { $_.nombre -eq $nombre } | Select-Object -First 1).pid }

# Muestra la causa probable de una caída: la primera línea de error del log y las últimas líneas.
function Explicar-Caida($log) {
  if (-not (Test-Path $log)) { return }
  $causa = Select-String -Path $log -Pattern 'Caused by: (?!org\.springframework)|APPLICATION FAILED TO START|Description:|ERROR' |
    Select-Object -Last 3
  foreach ($c in $causa) { Nota $c.Line.Trim() }
  Nota "log completo: $log"
}

$fallidos = @()
foreach ($s in $Servicios) {
  $p = [int](Valor $s.Clave $s.Defecto)
  $log = Join-Path $Logs "$($s.Nombre).log"
  $seg = Esperar $s.Texto { Responde "http://127.0.0.1:$p/actuator/health" '"UP"' } 240 (Pid-De $s.Nombre)
  if ($seg -ge 0) { Bien ("{0,-44} puerto {1,-6} ({2} s)" -f $s.Texto, $p, $seg) }
  elseif ($seg -eq -2) { Mal "$($s.Texto) se cayó al arrancar:"; Explicar-Caida $log; $fallidos += $s.Nombre }
  else { Mal "$($s.Texto) no respondió en el puerto $p en 4 minutos:"; Explicar-Caida $log; $fallidos += $s.Nombre }
}

# Historial de registro append-only (D60, ASR7): ahora que Flyway creó las tablas, el usuario de la app queda con
# solo INSERT y SELECT sobre hallazgo_version y hallazgo_auditoria. Se comprueba intentando borrar con esa credencial.
if ($Perfil -eq 'local' -and $fallidos -notcontains 'servicio-cientifico') {
  $r = Permisos-MySQL 'mysql-transaccional'
  if ($r.Ok -and $r.Historial -like 'historial protegido*') { Bien $r.Historial }
  elseif ($r.Ok) { Aviso "$($r.Historial)" }
  else { Mal 'el historial NO quedó protegido:'; $r.Lineas | ForEach-Object { Nota $_ } }
}

# ------------------------------------------------------------------------------------- 7. webs
Titulo '7/7 Webs'
foreach ($w in $Webs) {
  $log = Join-Path $Logs "$($w.Nombre).log"
  $seg = Esperar $w.Texto { Responde "http://localhost:$($w.Puerto)/" $null } 90 (Pid-De $w.Nombre)
  if ($seg -ge 0) { Bien ("{0,-44} puerto {1,-6} ({2} s)" -f $w.Texto, $w.Puerto, $seg) }
  else { Mal "$($w.Texto) no respondió. Últimas líneas de $log :"; Ultimas-Lineas $log 15; $fallidos += $w.Nombre }
}

if ($Sembrar) {
  Titulo 'Datos de ejemplo'
  if ($Perfil -ne 'local') { Aviso 'no siembro datos con -Perfil aiven: la base es compartida' }
  elseif ($fallidos.Count -gt 0) { Aviso 'no siembro datos: hay servicios caídos' }
  else {
    $env:BIOHUB_GATEWAY = "http://localhost:$gw"
    $env:BIOHUB_KEYCLOAK = "http://localhost:$kc"
    $env:BIOHUB_CLAVE_PRUEBA = Valor 'KEYCLOAK_TEST_USERS_PASSWORD' ''
    & node (Join-Path $Raiz 'scripts\sembrar-demo.mjs')
    Remove-Item env:BIOHUB_CLAVE_PRUEBA -ErrorAction SilentlyContinue
  }
}

$caidos = Mostrar-Tabla
Mostrar-Usuarios

Titulo 'Listo'
Write-Host "   Web interna (con login):  http://localhost:5173" -ForegroundColor Green
Write-Host "   Web pública (sin login):  http://localhost:5174" -ForegroundColor Green
Nota ''
Nota "Logs de cada servicio: $Logs"
Nota 'Ver el estado:       .\levantar.ps1 -Estado'
Nota 'Apagar todo:         .\levantar.ps1 -Detener'
Nota 'Datos de ejemplo:    .\levantar.ps1 -Sembrar'
Nota "Tardó $([int]((Get-Date) - $Inicio).TotalSeconds) s."
Write-Host ''
if ($caidos -gt 0) { exit 1 }
exit 0
