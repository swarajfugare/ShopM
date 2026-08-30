<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Utils\Response;
use PDO;

class ReportsController {
    public static function export(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $type = $_GET['type'] ?? 'sales'; // sales, expenses, customers, profit
        $fromDate = $_GET['from_date'] ?? date('Y-m-01');
        $toDate = $_GET['to_date'] ?? date('Y-m-d');
        $format = strtolower($_GET['format'] ?? 'json'); // json or csv

        $pdo = Database::getConnection();

        if ($type === 'sales') {
            $stmt = $pdo->prepare("
                SELECT b.bill_number, b.bill_date, b.sale_type, c.name as customer_name, c.mobile as customer_phone,
                       b.subtotal, b.discount_amount, b.final_amount, b.estimated_profit, b.actual_profit,
                       b.payment_method, b.payment_status
                FROM bills b
                LEFT JOIN customers c ON c.id = b.customer_id
                WHERE b.shop_id = ? AND DATE(b.bill_date) BETWEEN ? AND ? AND b.is_voided = 0
                ORDER BY b.bill_date DESC
            ");
            $stmt->execute([$shopId, $fromDate, $toDate]);
            $data = $stmt->fetchAll(PDO::FETCH_ASSOC);
        } elseif ($type === 'expenses') {
            $stmt = $pdo->prepare("
                SELECT expense_date, category, amount, payment_method, note
                FROM expenses
                WHERE shop_id = ? AND expense_date BETWEEN ? AND ?
                ORDER BY expense_date DESC
            ");
            $stmt->execute([$shopId, $fromDate, $toDate]);
            $data = $stmt->fetchAll(PDO::FETCH_ASSOC);
        } else {
            $stmt = $pdo->prepare("
                SELECT name, mobile, email, total_bills, lifetime_spend, first_purchase_at, last_purchase_at
                FROM customers
                WHERE shop_id = ? AND is_active = 1
                ORDER BY lifetime_spend DESC
            ");
            $stmt->execute([$shopId]);
            $data = $stmt->fetchAll(PDO::FETCH_ASSOC);
        }

        if ($format === 'csv') {
            header('Content-Type: text/csv; charset=utf-8');
            header("Content-Disposition: attachment; filename=\"matoshree_{$type}_report.csv\"");
            $out = fopen('php://output', 'w');
            if (!empty($data)) {
                fputcsv($out, array_keys($data[0]));
                foreach ($data as $row) {
                    fputcsv($out, $row);
                }
            }
            fclose($out);
            exit;
        }

        Response::success(['report_type' => $type, 'from' => $fromDate, 'to' => $toDate, 'rows' => $data], 'Report generated');
    }
}
