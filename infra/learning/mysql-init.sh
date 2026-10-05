#!/bin/bash
# Run by the official MySQL image on its first initialization only.
# Keep this file LF-terminated. Passwords are generated locally and never printed.
set -e

for variable in TICKETING_DB_PASSWORD TICKETING_MIGRATOR_PASSWORD; do
  value="${!variable}"
  if [[ ! "$value" =~ ^[a-f0-9]{64}$ ]]; then
    echo "Invalid learning database password format for $variable" >&2
    exit 1
  fi
done

# Do not depend on entrypoint shell functions: Windows bind mounts can appear executable.
MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?}" mysql --protocol=socket --user=root <<-EOSQL
  CREATE DATABASE ticketing_learning CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  CREATE USER 'ticketing_learning_app'@'%' IDENTIFIED BY '${TICKETING_DB_PASSWORD}';
  CREATE USER 'ticketing_learning_migrator'@'%' IDENTIFIED BY '${TICKETING_MIGRATOR_PASSWORD}';
  GRANT SELECT, INSERT, UPDATE, DELETE ON ticketing_learning.* TO 'ticketing_learning_app'@'%';
  GRANT ALL PRIVILEGES ON ticketing_learning.* TO 'ticketing_learning_migrator'@'%';
EOSQL
