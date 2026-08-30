<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Services\AuditService;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;

class ExpensesController {
    public static function index(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $pdo = Database::getConnection();

        $category = $_GET['category'] ?? null;
        $fromDate = $_GET['from_date'] ?? null;
        $toDate = $_GET['to_date'] ?? null;

        $sql = "SELECT e.*, u.name as created_by_name FROM expenses e LEFT JOIN users u ON u.id = e.created_by WHERE e.shop_id = :shop_id";
        $params = [':shop_id' => $shopId];

        if ($category) {
            $sql .= " AND e.category = :category";
            $params[':category'] = $category;
        }

        if ($fromDate && $toDate) {
            $sql .= " AND e.expense_date BETWEEN :from_date AND :to_date";
            $params[':from_date'] = $fromDate;
            $params[':to_date'] = $toDate;
        } else {
            // Default to current month
            $sql .= " AND YEAR(e.expense_date) = YEAR(CURDATE()) AND MONTH(e.expense_date) = MONTH(CURDATE())";
        }

        $sql .= " ORDER BY e.expense_date DESC, e.id DESC";

        $stmt = $pdo->prepare($sql);
        $stmt->execute($params);
        $expenses = $stmt->fetchAll(PDO::FETCH_ASSOC);

        // Calculate total expenses
        $total = 0.0;
        foreach ($expenses as $ex) {
            $total += (float)$ex['amount'];
        }

        Response::success([
            'expenses' => $expenses,
            'total_amount' => $total
        ], 'Expenses retrieved');
    }

    public static function create(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();

        $category = strtoupper(Validator::sanitizeString($input['category'] ?? 'OTHER'));
        $amount = (float)($input['amount'] ?? 0.0);
        $paymentMethod = strtoupper(Validator::sanitizeString($input['payment_method'] ?? 'CASH'));
        $expenseDate = Validator::sanitizeString($input['expense_date'] ?? date('Y-m-d'));
        $note = Validator::sanitizeString($input['note'] ?? '');

        if ($amount <= 0) {
            Response::error('Expense amount must be greater than 0.', 422);
        }

        $allowedCategories = ['RENT', 'ELECTRICITY', 'SALARY', 'TRANSPORT', 'PACKAGING', 'MAINTENANCE', 'OTHER'];
        if (!in_array($category, $allowedCategories, true)) {
            $category = 'OTHER';
        }

        $pdo = Database::getConnection();
        $stmt = $pdo->prepare("
            INSERT INTO expenses (shop_id, category, amount, payment_method, expense_date, note, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        ");
        $stmt->execute([$shopId, $category, $amount, $paymentMethod, $expenseDate, $note, $userId]);
        $newId = (int)$pdo->lastInsertId();

        AuditService::log($pdo, $shopId, $userId, 'EXPENSES', $newId, 'CREATE', null, ['category' => $category, 'amount' => $amount]);

        Response::success([
            'id'             => $newId,
            'category'       => $category,
            'amount'         => $amount,
            'payment_method' => $paymentMethod,
            'expense_date'   => $expenseDate,
            'note'           => $note
        ], 'Expense recorded', 201);
    }
}
