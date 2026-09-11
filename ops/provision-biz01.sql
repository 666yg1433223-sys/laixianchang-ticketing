-- BIZ-01-I-001 only. Render placeholders in memory, never archive rendered SQL.
CREATE DATABASE ticketing_biz01 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE ticketing_biz01_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'ticketing_biz01_app'@'%' IDENTIFIED BY '{{bizApp}}';
CREATE USER 'ticketing_biz01_it_app'@'%' IDENTIFIED BY '{{itApp}}';
CREATE USER 'ticketing_biz01_migrator'@'%' IDENTIFIED BY '{{bizMigrator}}';
CREATE USER 'ticketing_biz01_it_migrator'@'%' IDENTIFIED BY '{{itMigrator}}';
GRANT SELECT,INSERT,UPDATE ON `ticketing\_biz01`.* TO 'ticketing_biz01_app'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `ticketing\_biz01\_it`.* TO 'ticketing_biz01_it_app'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON `ticketing\_biz01`.* TO 'ticketing_biz01_migrator'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON `ticketing\_biz01\_it`.* TO 'ticketing_biz01_it_migrator'@'%';
SHOW GRANTS FOR 'ticketing_biz01_app'@'%';
SHOW GRANTS FOR 'ticketing_biz01_it_app'@'%';
SHOW GRANTS FOR 'ticketing_biz01_migrator'@'%';
SHOW GRANTS FOR 'ticketing_biz01_it_migrator'@'%';
