<?php
declare(strict_types=1);

/**
 * Migration Runner for Matoshree Collection Database (Hostinger MySQL)
 * Can be run via CLI: php migrations/migrate.php
 */

require_once dirname(__DIR__) . '/config/config.php';
require_once dirname(__DIR__) . '/config/database.php';

use Matoshree\Config\Database;

echo "=== Matoshree Collection Database Migration ===\n";

try {
    $pdo = Database::getConnection();
    echo "[✓] Connected to MySQL successfully.\n";

    // Track applied migrations table
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS `_migrations` (
            `id` INT AUTO_INCREMENT PRIMARY KEY,
            `migration` VARCHAR(255) NOT NULL UNIQUE,
            `applied_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    ");

    $migrationFiles = glob(__DIR__ . '/*.sql');
    sort($migrationFiles);

    foreach ($migrationFiles as $file) {
        $migrationName = basename($file);
        
        $stmt = $pdo->prepare("SELECT COUNT(*) FROM `_migrations` WHERE `migration` = ?");
        $stmt->execute([$migrationName]);
        if ($stmt->fetchColumn() > 0) {
            echo "[i] Skipping already applied: {$migrationName}\n";
            continue;
        }

        echo "[>] Applying migration: {$migrationName}... ";
        $sql = file_get_contents($file);
        
        // Execute multi-query safely
        $pdo->exec($sql);
        
        $logStmt = $pdo->prepare("INSERT INTO `_migrations` (`migration`) VALUES (?)");
        $logStmt->execute([$migrationName]);
        echo "SUCCESS.\n";
    }

    echo "=== All migrations completed successfully! ===\n";
} catch (Throwable $e) {
    echo "\n[!] MIGRATION FAILED: " . $e->getMessage() . "\n";
    exit(1);
}
