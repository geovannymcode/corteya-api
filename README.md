# CorteYa API — fase 1

Esqueleto del backend multi-barbería: esquema PostgreSQL, dominio, persistencia JPA,
repositorios de consulta y salud. No hay API pública de reservas en esta fase.

## Requisitos y versiones

Versiones estables verificadas el **19 de octubre de 2026**, sin milestones, RC ni snapshots:

| Componente | Versión | Fuente oficial |
| --- | --- | --- |
| Java | 25 LTS | [Oracle](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) |
| Spring Boot | 4.1.1 | [Spring](https://docs.spring.io/spring-boot/system-requirements.html) |
| Kotlin y plugins JVM/Spring/JPA | 2.4.21 | [Kotlin](https://kotlinlang.org/docs/releases.html) |
| Gradle Wrapper | 9.8.2 | [Gradle](https://gradle.org/releases/) |
| PostgreSQL (Compose y Testcontainers) | 18.6 | [PostgreSQL](https://www.postgresql.org/support/versioning/) |
| JUnit Jupiter | 5.14.4 | [JUnit](https://docs.junit.org/5.14.4/release-notes.html) |
| Plugin dependency-management | 1.1.7 | [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.spring.dependency-management) |

Spring Boot administra las versiones compatibles de Hibernate, Flyway, Validation,
Actuator, JDBC y Testcontainers mediante su BOM. La excepción deliberada es JUnit:
se fija la última versión estable de la rama **5**, como pide esta fase, porque Boot 4
selecciona JUnit 6 por defecto. Los tests arrancan Spring con `SpringApplicationBuilder`
y los ciclos `@BeforeAll`/`@AfterAll` de JUnit 5, porque `SpringExtension` de Spring 7
requiere JUnit 6. No se agregan bibliotecas fuera del alcance solicitado.
Se mantienen Kotlin Reflect y Jackson Kotlin del esqueleto Initializr.

Necesitas JDK 25 y Docker Desktop o un motor Docker compatible con Compose v2 en ejecución.

## Arranque local

Desde la raíz del proyecto, en una terminal zsh/bash:

```sh
cp .env.example .env
set -a
source .env
set +a
docker compose -f docker-compose.yml up -d --wait
./gradlew bootRun --args='--spring.profiles.active=dev'
```

En otra terminal:

```sh
curl --fail http://localhost:8080/actuator/health
# {"status":"UP"}
```

`.env` contiene credenciales locales y está excluido del control de versiones. Compose
lo lee automáticamente; el comando `source` exporta las variables para Spring Boot.
Si cambias el nombre, puerto o credenciales de PostgreSQL, ajusta también `DB_URL`,
`DB_USERNAME` y `DB_PASSWORD`. Spring no carga `.env` automáticamente.
PostgreSQL escucha únicamente en `127.0.0.1`; sus datos persisten en el volumen
`postgres_data`. Para detenerlo conservando los datos:

```sh
docker compose -f docker-compose.yml down
```

Para arrancar **sin datos de ejemplo**, utiliza una base vacía y ejecuta `./gradlew bootRun`
sin el perfil `dev`. Las migraciones de producción solo leen `db/migration`.
El perfil `dev` agrega `db/dev/R__demo_data.sql`: Barbería Piloto, tres barberos,
cuatro servicios (COP 25.000, 40.000, 30.000 y 18.000), las doce asociaciones y horarios
lunes–sábado de 09:00 a 18:00 en la zona local. La semilla es idempotente y no crea
clientes ni citas. Desactivar `dev` no elimina datos ya sembrados; usa bases separadas.

## Arquitectura

Cada módulo (`barbershop`, `catalog`, `scheduling`, `booking`, `customer`) contiene:

- `domain`: modelos inmutables y enums, sin dependencias de Spring ni JPA.
- `application`: puertos de consulta que exigen el UUID de la barbería.
- `infrastructure/persistence`: entidades JPA, repositorios Spring Data internos y adaptadores.

Los repositorios extienden solo `Repository`, sin heredar `findById`, `findAll`, `save`
ni borrados globales. La raíz `barbershop` se consulta por su propio UUID. Los demás
recursos exigen tanto el UUID de la barbería como el del recurso. Las entidades usan
UUID de referencias en lugar de asociaciones con carga diferida; los modelos de dominio
no exponen proxies. Los puertos de escritura se añadirán junto a sus casos de uso.

## Invariantes de datos

`V1__initial_schema.sql` crea ocho tablas con UUID y auditoría `created_at`/`updated_at`.
Cada tabla dependiente lleva `barbershop_id` e índice. Las claves foráneas compuestas
impiden mezclar recursos de barberías distintas, incluso con SQL directo. Un trigger
impide cambiar la barbería propietaria de una fila existente. Las consultas de la
aplicación siguen siendo responsables de proporcionar la barbería autorizada.

- Citas y bloqueos: `Instant` ↔ `timestamptz`. JDBC y sesiones PostgreSQL usan UTC.
  PostgreSQL guarda el instante, no el identificador de zona original.
  La presentación futura convierte con `ZoneId.of("America/Bogota")`.
- Horario semanal: días ISO (1=lunes, 7=domingo), `LocalTime` sin zona; se interpreta
  en `barbershop.timezone`. Esta fase admite intervalos dentro del mismo día.
- Dinero: `Long` ↔ `BIGINT`, COP enteros; precios y señales no negativos,
  señal menor o igual al precio. Duraciones positivas, porcentaje 0–100.
- Cliente: únicamente nombre y teléfono E.164; único por barbería y teléfono.
- Cita: estados y origen como enums persistidos por nombre; referencia de pago corta
  y única globalmente. Aquí se almacena el código; todavía no se genera.
- `btree_gist` y `EXCLUDE USING gist` impiden intervalos solapados `[inicio, fin)`
  de un mismo barbero en `PENDING_PAYMENT` o `CONFIRMED`, también bajo concurrencia.
  Citas adyacentes son válidas. `CANCELLED`, `EXPIRED`, `COMPLETED` y `NO_SHOW` no bloquean.
- `ends_at > starts_at` se valida para citas y bloqueos. `@Version` protege las
  actualizaciones JPA de citas. Los triggers mantienen la auditoría incluso con SQL directo.

`hold_expires_at` es solo un dato en esta fase: un hold vencido sigue bloqueando hasta
que un futuro proceso cambie su estado a `EXPIRED`. Los bloqueos y horarios todavía no
se cruzan automáticamente con citas. No se implementa cálculo de disponibilidad.

Flyway requiere un usuario con permiso para crear el esquema y la extensión `btree_gist`.
Localmente lo proporciona el usuario de Compose. Hibernate solo valida el esquema
(`ddl-auto: validate`); nunca lo crea ni modifica. Una migración ya aplicada debe
conservarse: cualquier cambio posterior requiere una nueva versión.

Solo se expone `/actuator/health`, sin detalles internos, índice de Actuator ni endpoints
JMX. Las demás rutas de negocio no están implementadas.

## Pruebas

Con Docker en ejecución:

```sh
./gradlew clean test bootJar
```

No necesitas levantar Compose ni exportar `.env` para las pruebas: Testcontainers
inicia PostgreSQL 18.6 con puertos y credenciales efímeros; el arranque de cada
clase de integración recibe esa conexión y cierra aplicación y contenedor al terminar.
Los tests no se saltan si Docker no está disponible; fallan para hacer visible el problema.

Se verifica migración limpia y validación JPA, ausencia de semilla sin `dev`, carga de la
semilla con `dev`, solapamientos y concurrencia, barberos distintos, intervalos adyacentes,
estados que liberan horario, aislamiento de todos los repositorios, claves foráneas entre
barberías, E.164 y unicidad, auditoría, bloqueo optimista, Bogotá 09:00 ↔ UTC 14:00 y
exposición exclusiva de salud. Informe HTML: `build/reports/tests/test/index.html`.

## TODO — fases posteriores

- API de reservas y puertos de escritura con reglas de negocio y disponibilidad.
- Resolver barbería autorizada desde identidad autenticada; roles y controles BOLA/IDOR
  en endpoints. Los filtros y claves foráneas actuales son la base de persistencia.
- Expirar holds, aplicar anticipación de cancelación, generar referencias únicas con reintentos.
- Confirmación manual Bre-B, integración Wompi y webhooks a cuentas de los barberos.
- Recordatorios, notificaciones, gestión de consentimiento y derechos de datos personales.
- App Flutter, entrada QR/link y panel Vaadin.
