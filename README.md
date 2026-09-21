# fuse

Сборка: `./gradlew shadowJar` → `build/libs/fuse-all.jar`.
Запуск: `java -jar build/libs/fuse-all.jar <точка монтирования> <каталог-хранилище> [опции libfuse]`.
Остановка: Ctrl+C или `docker stop` (SIGTERM) — shutdown hook размонтирует ФС.
Если процесс убит: `fusermount3 -u <точка>` (под root — `umount <точка>`).

Запись на диск делает сам `SpyFs`. Сверх этого каждая запись отдаётся списку экшенов из
конструктора `SpyFs` — порядок в списке и есть порядок выполнения: `PartLogAction` (лог
частей), `S3Action` (multipart-загрузка). Ненужный экшен убирается оттуда же. `S3Action` требует переменных окружения `S3_ENDPOINT` и `S3_BUCKET`, ключи
берёт из `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`. Путь `/<uuid>/screen` в хранилище
кладётся ключом `recordings/screen/<uuid>`.

Загрузка в S3 идёт на виртуальных потоках, поток FUSE её не ждёт. Расход памяти ограничен
двумя величинами: `S3_INFLIGHT_MB` (байты частей в полёте, по умолчанию 64) и буфер на каждый
открытый файл — до 10 МиБ. Для ~100 одновременных записей берите `-Xmx1536m`. Если S3 не
успевает, запись ждёт `S3_ACQUIRE_TIMEOUT_SEC` (10) и отдаёт EIO — память не растёт. При
остановке процесс дожидается догрузки не дольше `S3_DRAIN_TIMEOUT_SEC` (60).

Об ошибке загрузки программа-писатель узнаёт только если продолжает писать (следующая запись
вернёт EIO): `close(2)` возвращает код из `flush()`, а загрузка к тому моменту ещё идёт.
Сломанная загрузка отменяется, битый объект в бакете не остаётся. От `kill -9` это не спасает —
заведите в бакете правило lifecycle `AbortIncompleteMultipartUpload`, иначе брошенные части
копятся и тарифицируются.

Лог пишется в stdout. Уровень по умолчанию — INFO
(видны только части ≥ 5 МиБ и предупреждения). Чтобы увидеть жизненный цикл файлов
(create/open/truncate/fsync/rename/unlink/release), поднимите корневой уровень до DEBUG
в `src/main/resources/logback.xml` (`<root level="DEBUG">`) и пересоберите.
Подготовка образа `amazoncorretto:25`: `dnf install -y findutils fuse3 fuse3-devel`
(findutils — для `xargs` в `gradlew`; fuse3 — `fusermount3`; fuse3-devel — симлинк
`libfuse3.so`, либо `ln -s /usr/lib64/libfuse3.so.3 /usr/lib64/libfuse3.so`).
Запуск в контейнере: `--device /dev/fuse --cap-add SYS_ADMIN`, при AppArmor ещё
`--security-opt apparmor=unconfined`. Программа-писатель должна работать в том же
контейнере — снаружи точка монтирования не видна.

Вне контейнера нужны `fuse3` и `libfuse3.so` в `java.library.path` (Ubuntu:
`libfuse3-dev`, `-Djava.library.path=/usr/lib/x86_64-linux-gnu`).
Если программа-писатель работает от другого пользователя — монтировать с
`-o allow_other` и раскомментировать `user_allow_other` в `/etc/fuse.conf`.
Если путь у неё зашит — монтировать поверх её каталога (libfuse 3 разрешает
монтирование в непустой каталог). Порядок остановки: сначала программа-писатель,
потом Ctrl+C.
