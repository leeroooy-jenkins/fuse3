# fuse

Сборка: `./gradlew shadowJar` → `build/libs/fuse-all.jar`.
Запуск: `java -jar build/libs/fuse-all.jar <точка монтирования> <каталог-хранилище> [опции libfuse]`.
Остановка: Ctrl+C или `docker stop` (SIGTERM) — shutdown hook размонтирует ФС.
Если процесс убит: `fusermount3 -u <точка>` (под root — `umount <точка>`).

Что происходит с записями, задаёт список экшенов в конструкторе `SpyFs` — порядок в
списке и есть порядок выполнения: `StorageAction` (зеркалирование в каталог-хранилище),
`PartLogAction` (лог частей), `S3Action` (multipart-загрузка). Ненужный экшен убирается
оттуда же. `S3Action` требует переменных окружения `S3_ENDPOINT` и `S3_BUCKET`, ключи
берёт из `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`. Путь `/<uuid>/screen` в хранилище
кладётся ключом `recordings/screen/<uuid>`.

Лог пишется в `fuse.log` в текущем каталоге и на консоль. Уровень по умолчанию — INFO
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
