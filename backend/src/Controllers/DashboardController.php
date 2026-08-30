<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Utils\Response;
use PDO;

class DashboardController {
    public static function index(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $pdo = Database::getConnection();

        // 1. Today's Metrics (Asia/Kolkata date)
        $today = date('Y-m-d');
        
        $todayStmt = $pdo->prepare("
            SELECT 
                COALESCE(SUM(final_amount), 0) as today_sales,
                COUNT(id) as today_bills,
                COALESCE(SUM(estimated_profit + actual_profit), 0) as today_profit
            FROM bills
            WHERE shop_id = ? AND DATE(bill_date) = ? AND is_voided = 0
        ");
        $todayStmt->execute([$shopId, $today]);
        $todayData = $todayStmt->fetch(PDO::FETCH_ASSOC);

        $todaySales = (float)$todayData['today_sales'];
        $todayBills = (int)$todayData['today_bills'];
        $todayProfit = (float)$todayData['today_profit'];
        $avgOrderValue = $todayBills > 0 ? round($todaySales / $todayBills, 2) : 0.0;

        // 2. Monthly Metrics & Target
        $currentYear = (int)date('Y');
        $currentMonth = (int)date('m');

        $monthStmt = $pdo->prepare("
            SELECT COALESCE(SUM(final_amount), 0) as month_sales, COUNT(id) as month_bills
            FROM bills
            WHERE shop_id = ? AND YEAR(bill_date) = ? AND MONTH(bill_date) = ? AND is_voided = 0
        ");
        $monthStmt->execute([$shopId, $currentYear, $currentMonth]);
        $monthData = $monthStmt->fetch(PDO::FETCH_ASSOC);
        $monthSales = (float)$monthData['month_sales'];

        // Get Monthly Target
        $targetStmt = $pdo->prepare("
            SELECT target_amount FROM targets
            WHERE shop_id = ? AND target_type = 'MONTHLY' AND year = ? AND month = ?
            LIMIT 1
        ");
        $targetStmt->execute([$shopId, $currentYear, $currentMonth]);
        $targetRow = $targetStmt->fetch(PDO::FETCH_ASSOC);
        $monthlyTarget = $targetRow ? (float)$targetRow['target_amount'] : 500000.00;
        $targetProgressPercent = $monthlyTarget > 0 ? min(100.0, round(($monthSales / $monthlyTarget) * 100, 1)) : 0.0;

        // 3. Payment Method Breakdown (Today)
        $payStmt = $pdo->prepare("
            SELECT 
                payment_method,
                COALESCE(SUM(amount), 0) as total_amount,
                COUNT(id) as count
            FROM payments
            WHERE bill_id IN (
                SELECT id FROM bills WHERE shop_id = ? AND DATE(bill_date) = ? AND is_voided = 0
            )
            GROUP BY payment_method
        ");
        $payStmt->execute([$shopId, $today]);
        $paymentBreakdown = $payStmt->fetchAll(PDO::FETCH_ASSOC);

        // Calculate payment percentages
        $totalPaymentsToday = 0.0;
        foreach ($paymentBreakdown as $p) {
            $totalPaymentsToday += (float)$p['total_amount'];
        }
        $formattedPayments = [];
        foreach ($paymentBreakdown as $p) {
            $amt = (float)$p['total_amount'];
            $pct = $totalPaymentsToday > 0 ? round(($amt / $totalPaymentsToday) * 100, 1) : 0.0;
            $formattedPayments[] = [
                'method'     => $p['payment_method'],
                'amount'     => $amt,
                'percentage' => $pct,
                'count'      => (int)$p['count']
            ];
        }

        // 4. Recent Bills (Latest 10)
        $recentStmt = $pdo->prepare("
            SELECT b.id, b.bill_number, b.transaction_uuid, b.sale_type, b.final_amount, b.estimated_profit,
                   b.actual_profit, b.payment_method, b.payment_status, b.is_voided, b.bill_date,
                   c.name as customer_name, c.mobile as customer_mobile
            FROM bills b
            LEFT JOIN customers c ON c.id = b.customer_id
            WHERE b.shop_id = ?
            ORDER BY b.bill_date DESC, b.id DESC
            LIMIT 10
        ");
        $recentStmt->execute([$shopId]);
        $recentBills = $recentStmt->fetchAll(PDO::FETCH_ASSOC);

        // 5. Authoritative Business Insight
        $insight = "Welcome back to Matoshree Collection. System operational.";
        if ($todayBills > 0) {
            $insight = "Generated {$todayBills} bills today with average order value of ₹" . number_format($avgOrderValue, 2);
        } elseif ($monthSales > 0) {
            $insight = "Month sales at ₹" . number_format($monthSales, 2) . " ({$targetProgressPercent}% of monthly target).";
        }

        Response::success([
            'today' => [
                'sales'         => $todaySales,
                'bills_count'   => $todayBills,
                'profit'        => $todayProfit,
                'avg_order'     => $avgOrderValue,
                'profit_margin' => 25.0
            ],
            'monthly' => [
                'sales'                  => $monthSales,
                'target'                 => $monthlyTarget,
                'target_progress_percent'=> $targetProgressPercent,
                'year'                   => $currentYear,
                'month'                  => $currentMonth
            ],
            'payment_breakdown' => $formattedPayments,
            'recent_bills'      => $recentBills,
            'insight'           => $insight
        ], 'Dashboard summary retrieved');
    }
}
