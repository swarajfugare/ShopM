<?php
declare(strict_types=1);

namespace Matoshree\Services;

use PDO;

class AuditService {
    public static function log(
        PDO $pdo,
        int $shopId,
        ?int $userId,
        string $entityType,
        ?int $entityId,
        string $action,
        ?array $oldData = null,
        ?array $newData = null
    ): void {
        try {
            $stmt = $pdo->prepare("
                INSERT INTO audit_logs (shop_id, user_id, entity_type, entity_id, action, old_data, new_data)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            ");
            $stmt->execute([
                $shopId,
                $userId,
                $entityType,
                $entityId,
                $action,
                $oldData ? json_encode($oldData) : null,
                $newData ? json_encode($newData) : null
            ]);
        } catch (\Throwable $e) {
            // Log silently so audit failure doesn't block primary transaction
            error_log("Audit log failed: " . $e->getMessage());
        }
    }
}
