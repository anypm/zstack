ALTER TABLE AlarmVO ADD COLUMN `repeatCount` int DEFAULT NULL;
UPDATE `AlarmVO` SET `repeatCount` = -1;