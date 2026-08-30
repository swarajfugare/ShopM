<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Services\AuditService;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;
use Throwable;

class CustomersController {
    public static function index(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $search = trim($_GET['search'] ?? '');
        $limit = min(100, max(1, (int)($_GET['limit'] ?? 50)));

        $pdo = Database::getConnection();

        if (!empty($search)) {
            $stmt = $pdo->prepare("
                SELECT id, shop_id, name, mobile, email, address, total_bills, lifetime_spend,
                       first_purchase_at, last_purchase_at, is_active
                FROM customers
                WHERE shop_id = ? AND is_active = 1 AND (name LIKE ? OR mobile LIKE ?)
                ORDER BY lifetime_spend DESC, name ASC
                LIMIT ?
            ");
            $like = "%{$search}%";
            $stmt->execute([$shopId, $like, $like, $limit]);
        } else {
            $stmt = $pdo->prepare("
                SELECT id, shop_id, name, mobile, email, address, total_bills, lifetime_spend,
                       first_purchase_at, last_purchase_at, is_active
                FROM customers
                WHERE shop_id = ? AND is_active = 1
                ORDER BY last_purchase_at DESC, lifetime_spend DESC
                LIMIT ?
            ");
            $stmt->execute([$shopId, $limit]);
        }

        $customers = $stmt->fetchAll(PDO::FETCH_ASSOC);

        // Add VIP tag logic
        foreach ($customers as &$c) {
            $spend = (float)$c['lifetime_spend'];
            $bills = (int)$c['total_bills'];
            $c['tier'] = ($spend >= 25000 || $bills >= 3) ? 'VIP' : 'REGULAR';
        }

        Response::success(['customers' => $customers], 'Customers retrieved');
    }

    public static function show(array $authUser, int $id): void {
        $shopId = (int)$authUser['shop_id'];
        $pdo = Database::getConnection();

        $stmt = $pdo->prepare("SELECT * FROM customers WHERE id = ? AND shop_id = ? LIMIT 1");
        $stmt->execute([$id, $shopId]);
        $customer = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$customer) {
            Response::notFound('Customer not found');
        }

        // Get past purchase bills
        $billsStmt = $pdo->prepare("
            SELECT id, bill_number, sale_type, final_amount, payment_method, payment_status, bill_date, is_voided
            FROM bills
            WHERE customer_id = ? AND shop_id = ?
            ORDER BY bill_date DESC
            LIMIT 20
        ");
        $billsStmt->execute([$id, $shopId]);
        $customer['bills'] = $billsStmt->fetchAll(PDO::FETCH_ASSOC);

        $spend = (float)$customer['lifetime_spend'];
        $bills = (int)$customer['total_bills'];
        $customer['tier'] = ($spend >= 25000 || $bills >= 3) ? 'VIP' : 'REGULAR';

        Response::success(['customer' => $customer], 'Customer profile retrieved');
    }

    public static function create(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();

        $name = Validator::sanitizeString($input['name'] ?? '');
        $mobile = Validator::sanitizeString($input['mobile'] ?? '');
        $email = Validator::sanitizeString($input['email'] ?? '');
        $address = Validator::sanitizeString($input['address'] ?? '');
        $notes = Validator::sanitizeString($input['notes'] ?? '');

        if (empty($name) || empty($mobile)) {
            Response::error('Customer name and mobile number are required.', 422);
        }

        $pdo = Database::getConnection();

        // Check if customer already exists by mobile
        $checkStmt = $pdo->prepare("SELECT id FROM customers WHERE shop_id = ? AND mobile = ? LIMIT 1");
        $checkStmt->execute([$shopId, $mobile]);
        $existingId = $checkStmt->fetchColumn();
        if ($existingId) {
            // Return existing customer
            self::show($authUser, (int)$existingId);
            return;
        }

        $insertStmt = $pdo->prepare("
            INSERT INTO customers (shop_id, name, mobile, email, address, notes, is_active)
            VALUES (?, ?, ?, ?, ?, ?, 1)
        ");
        $insertStmt->execute([$shopId, $name, $mobile, $email, $address, $notes]);
        $newId = (int)$pdo->lastInsertId();

        AuditService::log($pdo, $shopId, $userId, 'CUSTOMERS', $newId, 'CREATE', null, ['name' => $name, 'mobile' => $mobile]);

        self::show($authUser, $newId);
    }

    public static function update(array $authUser, int $id): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();

        $name = Validator::sanitizeString($input['name'] ?? '');
        $mobile = Validator::sanitizeString($input['mobile'] ?? '');
        $email = Validator::sanitizeString($input['email'] ?? '');
        $address = Validator::sanitizeString($input['address'] ?? '');
        $notes = Validator::sanitizeString($input['notes'] ?? '');

        if (empty($name) || empty($mobile)) {
            Response::error('Customer name and mobile number are required.', 422);
        }

        $pdo = Database::getConnection();
        $upStmt = $pdo->prepare("
            UPDATE customers
            SET name = ?, mobile = ?, email = ?, address = ?, notes = ?
            WHERE id = ? AND shop_id = ?
        ");
        $upStmt->execute([$name, $mobile, $email, $address, $notes, $id, $shopId]);

        AuditService::log($pdo, $shopId, $userId, 'CUSTOMERS', $id, 'UPDATE', null, ['name' => $name, 'mobile' => $mobile]);

        self::show($authUser, $id);
    }
}
