<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Utils\Response;
use PDO;

class AnalyticsController {
    /**
     * Daily Analytics (Hourly sales distribution 9AM–10PM)
     */
    public static function daily(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $date = $_GET['date'] ?? date('Y-m-d');
        $pdo = Database::getConnection();

        // 1. Hourly aggregation
        $hourlyStmt = $pdo->prepare("
            SELECT 
                HOUR(bill_date) as hour,
                COALESCE(SUM(final_amount), 0) as sales,
                COUNT(id) as bills_count
            FROM bills
            WHERE shop_id = ? AND DATE(bill_date) = ? AND is_voided = 0
            GROUP BY HOUR(bill_date)
            ORDER BY HOUR(bill_date) ASC
        ");
        $hourlyStmt->execute([$shopId, $date]);
        $hourlyRows = $hourlyStmt->fetchAll(PDO::FETCH_ASSOC);

        $hourlyMap = [];
        foreach ($hourlyRows as $row) {
            $hourlyMap[(int)$row['hour']] = [
                'sales' => (float)$row['sales'],
                'bills' => (int)$row['bills_count']
            ];
        }

        // Fill standard Indian retail hours (9 AM to 10 PM)
        $hourlyData = [];
        for ($h = 9; $h <= 22; $h++) {
            $label = date('g A', mktime($h, 0, 0, 1, 1));
            $hourlyData[] = [
                'hour'  => $h,
                'label' => $label,
                'sales' => $hourlyMap[$h]['sales'] ?? 0.0,
                'bills' => $hourlyMap[$h]['bills'] ?? 0
            ];
        }

        // Daily Summary
        $sumStmt = $pdo->prepare("
            SELECT 
                COALESCE(SUM(final_amount), 0) as total_sales,
                COUNT(id) as total_bills,
                COALESCE(SUM(estimated_profit + actual_profit), 0) as gross_profit
            FROM bills
            WHERE shop_id = ? AND DATE(bill_date) = ? AND is_voided = 0
        ");
        $sumStmt->execute([$shopId, $date]);
        $summary = $sumStmt->fetch(PDO::FETCH_ASSOC);

        Response::success([
            'date'         => $date,
            'summary'      => [
                'total_sales'  => (float)$summary['total_sales'],
                'total_bills'  => (int)$summary['total_bills'],
                'gross_profit' => (float)$summary['gross_profit'],
                'avg_bill'     => (int)$summary['total_bills'] > 0 ? round((float)$summary['total_sales'] / (int)$summary['total_bills'], 2) : 0.0
            ],
            'hourly_sales' => $hourlyData
        ], 'Daily analytics retrieved');
    }

    /**
     * Monthly Analytics (Daily points for the whole month + previous month comparison)
     */
    public static function monthly(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $year = (int)($_GET['year'] ?? date('Y'));
        $month = (int)($_GET['month'] ?? date('m'));
        $pdo = Database::getConnection();

        $daysInMonth = cal_days_in_month(CAL_GREGORIAN, $month, $year);

        // Fetch all bills for current month grouped by day
        $stmt = $pdo->prepare("
            SELECT 
                DAY(bill_date) as day,
                COALESCE(SUM(final_amount), 0) as sales,
                COALESCE(SUM(estimated_profit + actual_profit), 0) as profit,
                COUNT(id) as bills_count
            FROM bills
            WHERE shop_id = ? AND YEAR(bill_date) = ? AND MONTH(bill_date) = ? AND is_voided = 0
            GROUP BY DAY(bill_date)
            ORDER BY DAY(bill_date) ASC
        ");
        $stmt->execute([$shopId, $year, $month]);
        $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

        $dayMap = [];
        $totalMonthSales = 0.0;
        $totalMonthProfit = 0.0;
        $totalMonthBills = 0;

        foreach ($rows as $r) {
            $d = (int)$r['day'];
            $dayMap[$d] = [
                'sales'  => (float)$r['sales'],
                'profit' => (float)$r['profit'],
                'bills'  => (int)$r['bills_count']
            ];
            $totalMonthSales += (float)$r['sales'];
            $totalMonthProfit += (float)$r['profit'];
            $totalMonthBills += (int)$r['bills_count'];
        }

        $dailyPoints = [];
        for ($d = 1; $d <= $daysInMonth; $d++) {
            $dailyPoints[] = [
                'day'    => $d,
                'date'   => sprintf('%04d-%02d-%02d', $year, $month, $d),
                'sales'  => $dayMap[$d]['sales'] ?? 0.0,
                'profit' => $dayMap[$d]['profit'] ?? 0.0,
                'bills'  => $dayMap[$d]['bills'] ?? 0
            ];
        }

        // Previous Month comparison
        $prevMonth = $month === 1 ? 12 : $month - 1;
        $prevYear = $month === 1 ? $year - 1 : $year;
        $prevStmt = $pdo->prepare("
            SELECT COALESCE(SUM(final_amount), 0) as prev_sales
            FROM bills
            WHERE shop_id = ? AND YEAR(bill_date) = ? AND MONTH(bill_date) = ? AND is_voided = 0
        ");
        $prevStmt->execute([$shopId, $prevYear, $prevMonth]);
        $prevSales = (float)$prevStmt->fetchColumn();

        $growthPercent = $prevSales > 0 ? round((($totalMonthSales - $prevSales) / $prevSales) * 100, 1) : 0.0;

        Response::success([
            'year'                 => $year,
            'month'                => $month,
            'total_sales'          => $totalMonthSales,
            'total_profit'         => $totalMonthProfit,
            'total_bills'          => $totalMonthBills,
            'previous_month_sales' => $prevSales,
            'growth_percent'       => $growthPercent,
            'daily_points'         => $dailyPoints
        ], 'Monthly analytics retrieved');
    }

    /**
     * Yearly Analytics (Jan to Dec breakdown)
     */
    public static function yearly(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $year = (int)($_GET['year'] ?? date('Y'));
        $pdo = Database::getConnection();

        $stmt = $pdo->prepare("
            SELECT 
                MONTH(bill_date) as month,
                COALESCE(SUM(final_amount), 0) as sales,
                COALESCE(SUM(estimated_profit + actual_profit), 0) as profit,
                COUNT(id) as bills_count
            FROM bills
            WHERE shop_id = ? AND YEAR(bill_date) = ? AND is_voided = 0
            GROUP BY MONTH(bill_date)
            ORDER BY MONTH(bill_date) ASC
        ");
        $stmt->execute([$shopId, $year]);
        $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

        $monthMap = [];
        $totalYearSales = 0.0;
        $totalYearProfit = 0.0;
        $totalYearBills = 0;
        $bestMonth = ['month' => 1, 'sales' => 0.0];
        $lowestMonth = ['month' => 1, 'sales' => PHP_INT_MAX];

        foreach ($rows as $r) {
            $m = (int)$r['month'];
            $sales = (float)$r['sales'];
            $profit = (float)$r['profit'];
            $bills = (int)$r['bills_count'];

            $monthMap[$m] = ['sales' => $sales, 'profit' => $profit, 'bills' => $bills];
            $totalYearSales += $sales;
            $totalYearProfit += $profit;
            $totalYearBills += $bills;

            if ($sales > $bestMonth['sales']) {
                $bestMonth = ['month' => $m, 'sales' => $sales];
            }
            if ($sales < $lowestMonth['sales']) {
                $lowestMonth = ['month' => $m, 'sales' => $sales];
            }
        }

        $monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        $monthlyPoints = [];
        for ($m = 1; $m <= 12; $m++) {
            $monthlyPoints[] = [
                'month'      => $m,
                'month_name' => $monthNames[$m - 1],
                'sales'      => $monthMap[$m]['sales'] ?? 0.0,
                'profit'     => $monthMap[$m]['profit'] ?? 0.0,
                'bills'      => $monthMap[$m]['bills'] ?? 0
            ];
        }

        Response::success([
            'year'           => $year,
            'total_sales'    => $totalYearSales,
            'total_profit'   => $totalYearProfit,
            'total_bills'    => $totalYearBills,
            'best_month'     => $bestMonth,
            'lowest_month'   => $lowestMonth['sales'] === PHP_INT_MAX ? ['month' => 1, 'sales' => 0.0] : $lowestMonth,
            'monthly_points' => $monthlyPoints
        ], 'Yearly analytics retrieved');
    }
}
