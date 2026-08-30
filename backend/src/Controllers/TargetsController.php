<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;

class TargetsController {
    public static function index(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $year = (int)($_GET['year'] ?? date('Y'));
        $pdo = Database::getConnection();

        $stmt = $pdo->prepare("SELECT * FROM targets WHERE shop_id = ? AND year = ?");
        $stmt->execute([$shopId, $year]);
        $targets = $stmt->fetchAll(PDO::FETCH_ASSOC);

        Response::success(['year' => $year, 'targets' => $targets], 'Targets retrieved');
    }

    public static function set(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $input = Validator::getJsonInput();

        $targetType = strtoupper(Validator::sanitizeString($input['target_type'] ?? 'MONTHLY'));
        $year = (int)($input['year'] ?? date('Y'));
        $month = isset($input['month']) && $input['month'] !== '' ? (int)$input['month'] : null;
        $amount = (float)($input['target_amount'] ?? 0.0);

        if ($amount <= 0) {
            Response::error('Target amount must be greater than 0.', 422);
        }

        $pdo = Database::getConnection();
        $stmt = $pdo->prepare("
            INSERT INTO targets (shop_id, target_type, year, month, target_amount)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE target_amount = VALUES(target_amount), updated_at = NOW()
        ");
        $stmt->execute([$shopId, $targetType, $year, $month, $amount]);

        Response::success([
            'target_type'   => $targetType,
            'year'          => $year,
            'month'         => $month,
            'target_amount' => $amount
        ], 'Target saved successfully');
    }
}
