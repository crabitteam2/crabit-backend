# Local disposable simulation database only. Never deploy this image.
FROM postgres:16@sha256:f1c3376c26f2609ab9f29f71f824103fe2fcd8ee0346485cb6122a4f93df6f94
RUN apt-get update && apt-get install -y --no-install-recommends libfaketime=0.9.10+2024-06-05+gba9ed5b2-0.6 \
    && find /usr/lib -name libfaketime.so.1 -exec ln -s '{}' /usr/local/lib/simulation-faketime.so \; \
    && rm -rf /var/lib/apt/lists/*
# Only postgres and its children see the frozen clock. initdb runs at real time.
RUN mv /usr/lib/postgresql/16/bin/postgres /usr/lib/postgresql/16/bin/postgres-real
COPY postgres /usr/lib/postgresql/16/bin/postgres
RUN chmod 755 /usr/lib/postgresql/16/bin/postgres \
    && mkdir /simulation-clock && chmod 777 /simulation-clock \
    && printf '2026-05-31 15:00:00.000000\n' > /simulation-clock/current

COPY initialize-clock.sh /docker-entrypoint-initdb.d/99-simulation-clock.sh
