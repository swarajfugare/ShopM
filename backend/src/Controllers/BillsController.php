<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Services\AuditService;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;
use Throwable;

class BillsController {
    public static function index(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $pdo = Database::getConnection();

        $filter = $_GET['filter'] ?? 'all'; // today, this_week, this_month, custom, all
        $search = trim($_GET['search'] ?? '');
        $status = $_GET['status'] ?? null;

        $sql = "
            SELECT b.*, c.name as customer_name, c.mobile as customer_mobile
            FROM bills b
            LEFT JOIN customers c ON c.id = b.customer_id
            WHERE b.shop_id = :shop_id
        ";
        $params = [':shop_id' => $shopId];

        if ($filter === 'today') {
            $sql .= " AND DATE(b.bill_date) = CURDATE()";
        } elseif ($filter === 'this_week') {
            $sql .= " AND YEARWEEK(b.bill_date, 1) = YEARWEEK(CURDATE(), 1)";
        } elseif ($filter === 'this_month') {
            $sql .= " AND YEAR(b.bill_date) = YEAR(CURDATE()) AND MONTH(b.bill_date) = MONTH(CURDATE())";
        } elseif (!empty($_GET['from_date']) && !empty($_GET['to_date'])) {
            $sql .= " AND DATE(b.bill_date) BETWEEN :from_date AND :to_date";
            $params[':from_date'] = $_GET['from_date'];
            $params[':to_date'] = $_GET['to_date'];
        }

        if (!empty($search)) {
            $sql .= " AND (b.bill_number LIKE :search OR c.name LIKE :search OR c.mobile LIKE :search)";
            $params[':search'] = "%{$search}%";
        }

        if ($status !== null && $status !== '') {
            $sql .= " AND b.payment_status = :status";
            $params[':status'] = $status;
        }

        $sql .= " ORDER BY b.bill_date DESC, b.id DESC LIMIT 200";

        $stmt = $pdo->prepare($sql);
        $stmt->execute($params);
        $bills = $stmt->fetchAll(PDO::FETCH_ASSOC);

        Response::success(['bills' => $bills], 'Bills retrieved successfully');
    }

    public static function show(array $authUser, int $billId): void {
        $shopId = (int)$authUser['shop_id'];
        $pdo = Database::getConnection();

        $stmt = $pdo->prepare("
            SELECT b.*, c.name as customer_name, c.mobile as customer_mobile, c.email as customer_email,
                   s.name as shop_name, s.address as shop_address, s.gst_number as shop_gst, s.mobile as shop_mobile
            FROM bills b
            JOIN shops s ON s.id = b.shop_id
            LEFT JOIN customers c ON c.id = b.customer_id
            WHERE b.id = ? AND b.shop_id = ?
            LIMIT 1
        ");
        $stmt->execute([$billId, $shopId]);
        $bill = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$bill) {
            Response::notFound('Bill not found');
        }

        // Fetch bill items
        $itemStmt = $pdo->prepare("SELECT * FROM bill_items WHERE bill_id = ?");
        $itemStmt->execute([$billId]);
        $bill['items'] = $itemStmt->fetchAll(PDO::FETCH_ASSOC);

        // Fetch payments
        $payStmt = $pdo->prepare("SELECT * FROM payments WHERE bill_id = ?");
        $payStmt->execute([$billId]);
        $bill['payments'] = $payStmt->fetchAll(PDO::FETCH_ASSOC);

        Response::success(['bill' => $bill], 'Bill details retrieved');
    }

    public static function void(array $authUser, int $billId): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();
        $reason = Validator::sanitizeString($input['reason'] ?? 'Voided by boutique manager');

        $pdo = Database::getConnection();
        $pdo->beginTransaction();

        try {
            $stmt = $pdo->prepare("SELECT * FROM bills WHERE id = ? AND shop_id = ? FOR UPDATE");
            $stmt->execute([$billId, $shopId]);
            $bill = $stmt->fetch(PDO::FETCH_ASSOC);

            if (!$bill) {
                $pdo->rollBack();
                Response::notFound('Bill not found');
            }

            if ((int)$bill['is_voided'] === 1) {
                $pdo->rollBack();
                Response::error('Bill is already voided', 400);
            }

            // Mark bill as VOID
            $upStmt = $pdo->prepare("
                UPDATE bills 
                SET is_voided = 1, payment_status = 'VOID', void_reason = ?, voided_at = NOW()
                WHERE id = ?
            ");
            $upStmt->execute([$reason, $billId]);

            // Revert customer lifetime stats if customer was linked
            if ($bill['customer_id']) {
                $custStmt = $pdo->prepare("
                    UPDATE customers 
                    SET total_bills = GREATEST(0, total_bills - 1),
                        lifetime_spend = GREATEST(0.00, lifetime_spend - ?)
                    WHERE id = ? AND shop_id = ?
                ");
                $custStmt->execute([(float)$bill['final_amount'], (int)$bill['customer_id'], $shopId]);
            }

            // Revert inventory stock if tracked
            $itemsStmt = $pdo->prepare("SELECT product_id, quantity FROM bill_items WHERE bill_id = ?");
            $itemsStmt->execute([$billId]);
            $items = $itemsStmt->fetchAll(PDO::FETCH_ASSOC);
            foreach ($items as $item) {
                if ($item['product_id']) {
                    $pdo->prepare("UPDATE products SET current_stock = current_stock + ? WHERE id = ? AND track_inventory = 1")
                        ->execute([$item['quantity'], $item['product_id']]);
                }
            }

            // Audit
            AuditService::log($pdo, $shopId, $userId, 'BILLS', $billId, 'VOID', ['status' => 'PAID'], ['status' => 'VOID', 'reason' => $reason]);

            $pdo->commit();
            Response::success(null, 'Bill voided successfully');
        } catch (Throwable $e) {
            $pdo->rollBack();
            Response::error('Failed to void bill: ' . $e->getMessage(), 500);
        }
    }
}
