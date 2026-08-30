<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Services\FinancialCalculationService;
use Matoshree\Services\AuditService;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;
use Throwable;

class SyncController {
    /**
     * Batch Sync Ingestion Endpoint (POST /api/v1/sync)
     */
    public static function process(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();

        $deviceId = Validator::sanitizeString($input['device_id'] ?? 'android_pos_1');
        $items = $input['sync_items'] ?? [];

        if (empty($items) || !is_array($items)) {
            Response::error('Sync payload must contain an array of sync_items.', 422);
        }

        $pdo = Database::getConnection();

        // Register/update device last sync
        try {
            $devStmt = $pdo->prepare("
                INSERT INTO devices (shop_id, device_uuid, device_name, platform, app_version, last_sync_at)
                VALUES (?, ?, ?, 'Android', '1.0.0', NOW())
                ON DUPLICATE KEY UPDATE last_sync_at = NOW()
            ");
            $devStmt->execute([$shopId, $deviceId, $deviceId]);
        } catch (\Throwable $e) {
            // Ignore device logging errors
        }

        $results = [];

        foreach ($items as $syncItem) {
            $txUuid = Validator::sanitizeString($syncItem['transaction_uuid'] ?? '');
            $entityType = strtoupper(Validator::sanitizeString($syncItem['entity_type'] ?? ''));
            $operation = strtoupper(Validator::sanitizeString($syncItem['operation'] ?? 'CREATE'));
            $payload = $syncItem['payload'] ?? [];

            if (empty($txUuid) || empty($entityType)) {
                $results[] = [
                    'transaction_uuid' => $txUuid,
                    'status'           => 'FAILED',
                    'error'            => 'Missing transaction_uuid or entity_type'
                ];
                continue;
            }

            // Check if already processed
            $logCheck = $pdo->prepare("
                SELECT status FROM sync_logs
                WHERE shop_id = ? AND transaction_uuid = ?
                LIMIT 1
            ");
            $logCheck->execute([$shopId, $txUuid]);
            $existingLog = $logCheck->fetch(PDO::FETCH_ASSOC);

            if ($existingLog && $existingLog['status'] === 'SUCCESS') {
                $results[] = [
                    'transaction_uuid' => $txUuid,
                    'status'           => 'DUPLICATE',
                    'message'          => 'Already synchronized previously'
                ];
                continue;
            }

            // Process based on entity type
            try {
                if ($entityType === 'SALE') {
                    $res = self::syncSale($pdo, $shopId, $userId, $deviceId, $txUuid, $payload);
                    $results[] = $res;
                } elseif ($entityType === 'EXPENSE') {
                    $res = self::syncExpense($pdo, $shopId, $userId, $txUuid, $payload);
                    $results[] = $res;
                } elseif ($entityType === 'CUSTOMER') {
                    $res = self::syncCustomer($pdo, $shopId, $txUuid, $payload);
                    $results[] = $res;
                } else {
                    $results[] = [
                        'transaction_uuid' => $txUuid,
                        'status'           => 'FAILED',
                        'error'            => "Unsupported entity_type: {$entityType}"
                    ];
                }
            } catch (Throwable $e) {
                $results[] = [
                    'transaction_uuid' => $txUuid,
                    'status'           => 'FAILED',
                    'error'            => $e->getMessage()
                ];
            }
        }

        Response::success([
            'synced_at' => date('Y-m-d H:i:s'),
            'results'   => $results
        ], 'Batch sync processed');
    }

    private static function syncSale(PDO $pdo, int $shopId, int $userId, string $deviceId, string $txUuid, array $payload): array {
        // Check bills table for duplicate UUID
        $checkStmt = $pdo->prepare("SELECT id, bill_number FROM bills WHERE shop_id = ? AND transaction_uuid = ? LIMIT 1");
        $checkStmt->execute([$shopId, $txUuid]);
        $existing = $checkStmt->fetch(PDO::FETCH_ASSOC);
        if ($existing) {
            return [
                'transaction_uuid' => $txUuid,
                'status'           => 'DUPLICATE',
                'bill_id'          => (int)$existing['id'],
                'bill_number'      => $existing['bill_number']
            ];
        }

        $saleType = strtoupper($payload['sale_type'] ?? 'DETAILED');
        $customerId = isset($payload['customer_id']) && $payload['customer_id'] ? (int)$payload['customer_id'] : null;
        $paymentMethod = strtoupper($payload['payment_method'] ?? 'CASH');
        $note = $payload['note'] ?? '';
        $billDate = $payload['bill_date'] ?? date('Y-m-d H:i:s');
        $billNumber = $payload['bill_number'] ?? null;

        $pdo->beginTransaction();

        try {
            if (!$billNumber) {
                $currentYear = date('Y', strtotime($billDate));
                $seqStmt = $pdo->prepare("SELECT COUNT(id) + 1 FROM bills WHERE shop_id = ? AND YEAR(bill_date) = ?");
                $seqStmt->execute([$shopId, $currentYear]);
                $nextSeq = (int)$seqStmt->fetchColumn();
                $billNumber = sprintf('MC-%s-%06d', $currentYear, $nextSeq);
            }

            $subtotal = 0.0;
            $totalDiscount = (float)($payload['discount_amount'] ?? 0.0);
            $totalCost = 0.0;
            $estimatedProfit = 0.0;
            $actualProfit = 0.0;
            $profitType = 'ESTIMATED';
            $processedItems = [];

            if ($saleType === 'DETAILED') {
                $items = $payload['items'] ?? [];
                foreach ($items as $item) {
                    $fin = FinancialCalculationService::calculateItemFinancials(
                        (float)($item['selling_price'] ?? 0.0),
                        (int)($item['quantity'] ?? 1),
                        isset($item['cost_price']) ? (float)$item['cost_price'] : null,
                        (float)($item['discount_amount'] ?? 0.0),
                        25.0
                    );
                    $subtotal += ((float)$item['selling_price'] * (int)($item['quantity'] ?? 1));
                    $totalCost += $fin['line_cost'];
                    if ($fin['profit_type'] === 'ACTUAL') {
                        $actualProfit += $fin['line_profit'];
                        $profitType = 'ACTUAL';
                    } else {
                        $estimatedProfit += $fin['line_profit'];
                    }
                    $processedItems[] = array_merge($item, $fin);
                }
                $finalAmount = max(0.0, round($subtotal - $totalDiscount, 2));
            } else {
                $finalAmount = (float)($payload['final_amount'] ?? 0.0);
                $qFin = FinancialCalculationService::calculateQuickSaleFinancials($finalAmount, 25.0);
                $subtotal = $qFin['subtotal'];
                $totalCost = $qFin['cost_amount'];
                $estimatedProfit = $qFin['estimated_profit'];
            }

            $bStmt = $pdo->prepare("
                INSERT INTO bills (
                    shop_id, customer_id, bill_number, transaction_uuid, sale_type,
                    subtotal, discount_amount, tax_amount, final_amount, cost_amount,
                    estimated_profit, actual_profit, profit_type, payment_method, payment_status,
                    note, bill_date, created_by, device_id, sync_status
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, 'PAID',
                    ?, ?, ?, ?, 'SYNCED'
                )
            ");
            $bStmt->execute([
                $shopId, $customerId, $billNumber, $txUuid, $saleType,
                $subtotal, $totalDiscount, 0.0, $finalAmount, $totalCost,
                $estimatedProfit, $actualProfit, $profitType, $paymentMethod,
                $note, $billDate, $userId, $deviceId
            ]);
            $billId = (int)$pdo->lastInsertId();

            if (!empty($processedItems)) {
                $itStmt = $pdo->prepare("
                    INSERT INTO bill_items (
                        bill_id, product_id, product_name_snapshot, sku_snapshot, category_id,
                        quantity, selling_price, cost_price, discount_amount, line_total, line_cost, line_profit
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ");
                foreach ($processedItems as $it) {
                    $itStmt->execute([
                        $billId, $it['product_id'] ?? null, $it['name'] ?? $it['product_name_snapshot'] ?? 'Boutique Item',
                        $it['sku'] ?? $it['sku_snapshot'] ?? null, $it['category_id'] ?? null,
                        $it['quantity'] ?? 1, $it['selling_price'] ?? 0.0, $it['cost_price'] ?? null,
                        $it['discount_amount'] ?? 0.0, $it['line_total'], $it['line_cost'], $it['line_profit']
                    ]);
                }
            }

            $pdo->prepare("INSERT INTO payments (bill_id, payment_method, amount, payment_date) VALUES (?, ?, ?, ?)")
                ->execute([$billId, $paymentMethod, $finalAmount, $billDate]);

            if ($customerId) {
                $pdo->prepare("
                    UPDATE customers 
                    SET total_bills = total_bills + 1, lifetime_spend = lifetime_spend + ?, last_purchase_at = ?
                    WHERE id = ? AND shop_id = ?
                ")->execute([$finalAmount, $billDate, $customerId, $shopId]);
            }

            $pdo->prepare("
                INSERT INTO sync_logs (shop_id, device_id, transaction_uuid, entity_type, entity_id, operation, status)
                VALUES (?, ?, ?, 'SALE', ?, 'CREATE', 'SUCCESS')
            ")->execute([$shopId, $deviceId, $txUuid, $billId]);

            $pdo->commit();

            return [
                'transaction_uuid' => $txUuid,
                'status'           => 'SUCCESS',
                'server_id'        => $billId,
                'bill_number'      => $billNumber
            ];
        } catch (Throwable $e) {
            $pdo->rollBack();
            return [
                'transaction_uuid' => $txUuid,
                'status'           => 'FAILED',
                'error'            => $e->getMessage()
            ];
        }
    }

    private static function syncExpense(PDO $pdo, int $shopId, int $userId, string $txUuid, array $payload): array {
        $category = strtoupper($payload['category'] ?? 'OTHER');
        $amount = (float)($payload['amount'] ?? 0.0);
        $paymentMethod = strtoupper($payload['payment_method'] ?? 'CASH');
        $expenseDate = $payload['expense_date'] ?? date('Y-m-d');
        $note = $payload['note'] ?? '';

        $stmt = $pdo->prepare("
            INSERT INTO expenses (shop_id, category, amount, payment_method, expense_date, note, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        ");
        $stmt->execute([$shopId, $category, $amount, $paymentMethod, $expenseDate, $note, $userId]);
        $newId = (int)$pdo->lastInsertId();

        return [
            'transaction_uuid' => $txUuid,
            'status'           => 'SUCCESS',
            'server_id'        => $newId
        ];
    }

    private static function syncCustomer(PDO $pdo, int $shopId, string $txUuid, array $payload): array {
        $name = $payload['name'] ?? '';
        $mobile = $payload['mobile'] ?? '';
        $email = $payload['email'] ?? '';
        $address = $payload['address'] ?? '';

        $stmt = $pdo->prepare("
            INSERT INTO customers (shop_id, name, mobile, email, address, is_active)
            VALUES (?, ?, ?, ?, ?, 1)
        ");
        $stmt->execute([$shopId, $name, $mobile, $email, $address]);
        $newId = (int)$pdo->lastInsertId();

        return [
            'transaction_uuid' => $txUuid,
            'status'           => 'SUCCESS',
            'server_id'        => $newId
        ];
    }
}
