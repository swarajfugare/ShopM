<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Services\AuditService;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;
use Throwable;

class DailyClosingController {
    /**
     * Preview calculation for closing a day
     */
    public static function preview(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $date = Validator::sanitizeString($_GET['date'] ?? date('Y-m-d'));

        $pdo = Database::getConnection();

        // Check if day is already closed
        $checkStmt = $pdo->prepare("
            SELECT dc.*, u.name as closed_by_name
            FROM daily_closings dc
            LEFT JOIN users u ON u.id = dc.closed_by
            WHERE dc.shop_id = ? AND dc.closing_date = ?
            LIMIT 1
        ");
        $checkStmt->execute([$shopId, $date]);
        $existing = $checkStmt->fetch(PDO::FETCH_ASSOC);

        if ($existing) {
            Response::success([
                'is_closed' => true,
                'closing'   => $existing
            ], 'Day is already closed');
            return;
        }

        // Calculate active financial totals for the date
        $salesStmt = $pdo->prepare("
            SELECT 
                COALESCE(SUM(final_amount), 0) as total_sales,
                COUNT(id) as total_bills,
                COALESCE(SUM(CASE WHEN payment_method = 'CASH' THEN final_amount ELSE 0 END), 0) as cash_sales,
                COALESCE(SUM(CASE WHEN payment_method = 'UPI' THEN final_amount ELSE 0 END), 0) as upi_sales,
                COALESCE(SUM(CASE WHEN payment_method = 'CARD' THEN final_amount ELSE 0 END), 0) as card_sales,
                COALESCE(SUM(CASE WHEN payment_method NOT IN ('CASH', 'UPI', 'CARD') THEN final_amount ELSE 0 END), 0) as other_sales,
                COALESCE(SUM(estimated_profit + actual_profit), 0) as gross_profit
            FROM bills
            WHERE shop_id = ? AND DATE(bill_date) = ? AND is_voided = 0
        ");
        $salesStmt->execute([$shopId, $date]);
        $sales = $salesStmt->fetch(PDO::FETCH_ASSOC);

        // Calculate expenses for the date
        $expStmt = $pdo->prepare("
            SELECT 
                COALESCE(SUM(amount), 0) as total_expenses,
                COALESCE(SUM(CASE WHEN payment_method = 'CASH' THEN amount ELSE 0 END), 0) as cash_expenses
            FROM expenses
            WHERE shop_id = ? AND expense_date = ?
        ");
        $expStmt->execute([$shopId, $date]);
        $exp = $expStmt->fetch(PDO::FETCH_ASSOC);

        $totalSales = (float)$sales['total_sales'];
        $totalBills = (int)$sales['total_bills'];
        $cashSales = (float)$sales['cash_sales'];
        $upiSales = (float)$sales['upi_sales'];
        $cardSales = (float)$sales['card_sales'];
        $otherSales = (float)$sales['other_sales'];
        $grossProfit = (float)$sales['gross_profit'];

        $totalExpenses = (float)$exp['total_expenses'];
        $cashExpenses = (float)$exp['cash_expenses'];
        $netProfit = round($grossProfit - $totalExpenses, 2);

        // Expected Cash in Drawer = Cash Sales - Cash Expenses
        $expectedCash = max(0.0, round($cashSales - $cashExpenses, 2));

        Response::success([
            'is_closed'       => false,
            'closing_date'    => $date,
            'total_sales'     => $totalSales,
            'total_bills'     => $totalBills,
            'cash_sales'      => $cashSales,
            'upi_sales'       => $upiSales,
            'card_sales'      => $cardSales,
            'other_sales'     => $otherSales,
            'gross_profit'    => $grossProfit,
            'total_expenses'  => $totalExpenses,
            'net_profit'      => $netProfit,
            'expected_cash'   => $expectedCash
        ], 'Daily closing calculation preview');
    }

    /**
     * Submit official Day Closing
     */
    public static function submit(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();

        $closingDate = Validator::sanitizeString($input['closing_date'] ?? date('Y-m-d'));
        $actualCash = (float)($input['actual_cash'] ?? 0.0);
        $notes = Validator::sanitizeString($input['notes'] ?? '');

        $pdo = Database::getConnection();

        // 1. Calculate authoritative totals
        $salesStmt = $pdo->prepare("
            SELECT 
                COALESCE(SUM(final_amount), 0) as total_sales,
                COUNT(id) as total_bills,
                COALESCE(SUM(CASE WHEN payment_method = 'CASH' THEN final_amount ELSE 0 END), 0) as cash_sales,
                COALESCE(SUM(CASE WHEN payment_method = 'UPI' THEN final_amount ELSE 0 END), 0) as upi_sales,
                COALESCE(SUM(CASE WHEN payment_method = 'CARD' THEN final_amount ELSE 0 END), 0) as card_sales,
                COALESCE(SUM(CASE WHEN payment_method NOT IN ('CASH', 'UPI', 'CARD') THEN final_amount ELSE 0 END), 0) as other_sales,
                COALESCE(SUM(estimated_profit + actual_profit), 0) as gross_profit
            FROM bills
            WHERE shop_id = ? AND DATE(bill_date) = ? AND is_voided = 0
        ");
        $salesStmt->execute([$shopId, $closingDate]);
        $sales = $salesStmt->fetch(PDO::FETCH_ASSOC);

        $expStmt = $pdo->prepare("
            SELECT 
                COALESCE(SUM(amount), 0) as total_expenses,
                COALESCE(SUM(CASE WHEN payment_method = 'CASH' THEN amount ELSE 0 END), 0) as cash_expenses
            FROM expenses
            WHERE shop_id = ? AND expense_date = ?
        ");
        $expStmt->execute([$shopId, $closingDate]);
        $exp = $expStmt->fetch(PDO::FETCH_ASSOC);

        $totalSales = (float)$sales['total_sales'];
        $totalBills = (int)$sales['total_bills'];
        $cashSales = (float)$sales['cash_sales'];
        $upiSales = (float)$sales['upi_sales'];
        $cardSales = (float)$sales['card_sales'];
        $otherSales = (float)$sales['other_sales'];
        $grossProfit = (float)$sales['gross_profit'];

        $totalExpenses = (float)$exp['total_expenses'];
        $cashExpenses = (float)$exp['cash_expenses'];
        $netProfit = round($grossProfit - $totalExpenses, 2);

        $expectedCash = max(0.0, round($cashSales - $cashExpenses, 2));
        $cashDifference = round($actualCash - $expectedCash, 2);

        $pdo->beginTransaction();
        try {
            $stmt = $pdo->prepare("
                INSERT INTO daily_closings (
                    shop_id, closing_date, total_sales, total_bills, cash_sales, upi_sales,
                    card_sales, other_sales, gross_profit, total_expenses, net_profit,
                    expected_cash, actual_cash, cash_difference, notes, closed_by, closed_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, NOW()
                )
                ON DUPLICATE KEY UPDATE
                    total_sales = VALUES(total_sales),
                    total_bills = VALUES(total_bills),
                    cash_sales = VALUES(cash_sales),
                    upi_sales = VALUES(upi_sales),
                    card_sales = VALUES(card_sales),
                    other_sales = VALUES(other_sales),
                    gross_profit = VALUES(gross_profit),
                    total_expenses = VALUES(total_expenses),
                    net_profit = VALUES(net_profit),
                    expected_cash = VALUES(expected_cash),
                    actual_cash = VALUES(actual_cash),
                    cash_difference = VALUES(cash_difference),
                    notes = VALUES(notes),
                    closed_by = VALUES(closed_by),
                    closed_at = NOW()
            ");
            $stmt->execute([
                $shopId, $closingDate, $totalSales, $totalBills, $cashSales, $upiSales,
                $cardSales, $otherSales, $grossProfit, $totalExpenses, $netProfit,
                $expectedCash, $actualCash, $cashDifference, $notes, $userId
            ]);
            $closingId = (int)$pdo->lastInsertId();

            AuditService::log($pdo, $shopId, $userId, 'DAILY_CLOSING', $closingId, 'CLOSE_DAY', null, [
                'closing_date'    => $closingDate,
                'total_sales'     => $totalSales,
                'cash_difference' => $cashDifference
            ]);

            $pdo->commit();

            Response::success([
                'closing_date'    => $closingDate,
                'total_sales'     => $totalSales,
                'total_bills'     => $totalBills,
                'gross_profit'    => $grossProfit,
                'total_expenses'  => $totalExpenses,
                'net_profit'      => $netProfit,
                'expected_cash'   => $expectedCash,
                'actual_cash'     => $actualCash,
                'cash_difference' => $cashDifference,
                'notes'           => $notes
            ], 'Day closed and locked successfully');
        } catch (Throwable $e) {
            $pdo->rollBack();
            Response::error('Failed to submit daily closing: ' . $e->getMessage(), 500);
        }
    }
}
