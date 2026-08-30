<?php
declare(strict_types=1);

/**
 * Matoshree Collection — API Gateway & Central Router
 * Hostinger Business Hosting Compatible (PHP 8.1+)
 */

// Error Handling & Autoloading
ini_set('display_errors', '0');
error_reporting(E_ALL);

// Manual PSR-4 Fallback Autoloader for Matoshree namespace
spl_autoload_register(function (string $class) {
    $prefix = 'Matoshree\\';
    $baseDir = dirname(__DIR__) . '/src/';

    $len = strlen($prefix);
    if (strncmp($prefix, $class, $len) !== 0) {
        return;
    }

    $relativeClass = substr($class, $len);
    $file = $baseDir . str_replace('\\', '/', $relativeClass) . '.php';

    if (file_exists($file)) {
        require_once $file;
    }
});

// Load config
require_once dirname(__DIR__) . '/config/config.php';
require_once dirname(__DIR__) . '/config/database.php';

use Matoshree\Middleware\CorsMiddleware;
use Matoshree\Middleware\AuthMiddleware;
use Matoshree\Controllers\AuthController;
use Matoshree\Controllers\DashboardController;
use Matoshree\Controllers\SalesController;
use Matoshree\Controllers\BillsController;
use Matoshree\Controllers\CustomersController;
use Matoshree\Controllers\ProductsController;
use Matoshree\Controllers\CategoriesController;
use Matoshree\Controllers\ExpensesController;
use Matoshree\Controllers\DailyClosingController;
use Matoshree\Controllers\AnalyticsController;
use Matoshree\Controllers\TargetsController;
use Matoshree\Controllers\SettingsController;
use Matoshree\Controllers\SyncController;
use Matoshree\Controllers\ReportsController;
use Matoshree\Utils\Response;

// Apply CORS headers
CorsMiddleware::handle();

// Parse Request URI and Method
$uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?? '/';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

// Strip subdirectories if installed under subfolder on Hostinger
$apiPrefix = '/api/v1';
$pos = strpos($uri, $apiPrefix);
if ($pos !== false) {
    $path = substr($uri, $pos + strlen($apiPrefix));
} else {
    $path = $uri;
}
$path = rtrim($path, '/');
if (empty($path)) {
    $path = '/';
}

try {
    // -------------------------------------------------------------
    // PUBLIC ROUTES
    // -------------------------------------------------------------
    if ($path === '/' || $path === '/health') {
        Response::success([
            'service'   => 'Matoshree Collection REST API',
            'version'   => '1.0.0',
            'status'    => 'healthy',
            'timestamp' => time()
        ], 'API Gateway operational');
    }

    if ($path === '/auth/login' && $method === 'POST') {
        AuthController::login();
    }

    // -------------------------------------------------------------
    // PROTECTED ROUTES (Requires JWT Bearer Token)
    // -------------------------------------------------------------
    $authUser = AuthMiddleware::authenticate();

    // 1. Auth Me
    if ($path === '/auth/me' && $method === 'GET') {
        AuthController::me($authUser);
    }

    // 2. Dashboard
    if ($path === '/dashboard' && $method === 'GET') {
        DashboardController::index($authUser);
    }

    // 3. Sales (Atomic Creation)
    if ($path === '/sales' && $method === 'POST') {
        SalesController::create($authUser);
    }

    // 4. Bills
    if ($path === '/bills' && $method === 'GET') {
        BillsController::index($authUser);
    }
    if (preg_match('#^/bills/(\d+)$#', $path, $matches) && $method === 'GET') {
        BillsController::show($authUser, (int)$matches[1]);
    }
    if (preg_match('#^/bills/(\d+)/void$#', $path, $matches) && $method === 'POST') {
        BillsController::void($authUser, (int)$matches[1]);
    }

    // 5. Customers
    if ($path === '/customers' && $method === 'GET') {
        CustomersController::index($authUser);
    }
    if ($path === '/customers' && $method === 'POST') {
        CustomersController::create($authUser);
    }
    if (preg_match('#^/customers/(\d+)$#', $path, $matches) && $method === 'GET') {
        CustomersController::show($authUser, (int)$matches[1]);
    }
    if (preg_match('#^/customers/(\d+)$#', $path, $matches) && ($method === 'PUT' || $method === 'POST')) {
        CustomersController::update($authUser, (int)$matches[1]);
    }

    // 6. Products & Categories
    if ($path === '/products' && $method === 'GET') {
        ProductsController::index($authUser);
    }
    if ($path === '/products' && $method === 'POST') {
        ProductsController::create($authUser);
    }
    if (preg_match('#^/products/(\d+)$#', $path, $matches) && $method === 'GET') {
        ProductsController::show($authUser, (int)$matches[1]);
    }
    if (preg_match('#^/products/(\d+)$#', $path, $matches) && ($method === 'PUT' || $method === 'POST')) {
        ProductsController::update($authUser, (int)$matches[1]);
    }
    if ($path === '/categories' && $method === 'GET') {
        CategoriesController::index($authUser);
    }
    if ($path === '/categories' && $method === 'POST') {
        CategoriesController::create($authUser);
    }

    // 7. Expenses
    if ($path === '/expenses' && $method === 'GET') {
        ExpensesController::index($authUser);
    }
    if ($path === '/expenses' && $method === 'POST') {
        ExpensesController::create($authUser);
    }

    // 8. Daily Closing
    if ($path === '/daily-closing' && $method === 'GET') {
        DailyClosingController::preview($authUser);
    }
    if ($path === '/daily-closing' && $method === 'POST') {
        DailyClosingController::submit($authUser);
    }

    // 9. Analytics
    if ($path === '/analytics/daily' && $method === 'GET') {
        AnalyticsController::daily($authUser);
    }
    if ($path === '/analytics/monthly' && $method === 'GET') {
        AnalyticsController::monthly($authUser);
    }
    if ($path === '/analytics/yearly' && $method === 'GET') {
        AnalyticsController::yearly($authUser);
    }

    // 10. Targets
    if ($path === '/targets' && $method === 'GET') {
        TargetsController::index($authUser);
    }
    if ($path === '/targets' && $method === 'POST') {
        TargetsController::set($authUser);
    }

    // 11. Settings
    if ($path === '/settings' && $method === 'GET') {
        SettingsController::index($authUser);
    }
    if ($path === '/settings' && ($method === 'PUT' || $method === 'POST')) {
        SettingsController::update($authUser);
    }

    // 12. Batch Sync Ingestion (Offline Queue)
    if ($path === '/sync' && $method === 'POST') {
        SyncController::process($authUser);
    }

    // 13. Reports Export
    if ($path === '/reports' && $method === 'GET') {
        ReportsController::export($authUser);
    }

    // Unmatched Route
    Response::notFound("Endpoint {$method} {$path} not found on this server.");

} catch (\Throwable $e) {
    Response::error('Server Error: ' . $e->getMessage(), 500);
}
