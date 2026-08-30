<?php
declare(strict_types=1);

/**
 * Matoshree Collection - Backend Configuration Loader
 */

// Helper to load .env file if present
function loadEnv(string $path): void {
    if (!file_exists($path)) {
        return;
    }
    $lines = file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    foreach ($lines as $line) {
        $line = trim($line);
        if (empty($line) || str_starts_with($line, '#')) {
            continue;
        }
        $parts = explode('=', $line, 2);
        if (count($parts) === 2) {
            $key = trim($parts[0]);
            $val = trim($parts[1]);
            // Strip quotes if wrapped
            if ((str_starts_with($val, '"') && str_ends_with($val, '"')) ||
                (str_starts_with($val, "'") && str_ends_with($val, "'"))) {
                $val = substr($val, 1, -1);
            }
            if (!array_key_exists($key, $_ENV)) {
                $_ENV[$key] = $val;
                putenv("{$key}={$val}");
            }
        }
    }
}

// Attempt to load .env from backend root
loadEnv(dirname(__DIR__) . '/.env');

return [
    'db' => [
        'host'     => getenv('DB_HOST') ?: 'localhost',
        'port'     => (int)(getenv('DB_PORT') ?: 3306),
        'database' => getenv('DB_NAME') ?: 'matoshree_db',
        'username' => getenv('DB_USER') ?: 'root',
        'password' => getenv('DB_PASS') ?: '',
        'charset'  => getenv('DB_CHARSET') ?: 'utf8mb4',
    ],
    'jwt' => [
        'secret'      => getenv('JWT_SECRET') ?: 'matoshree_boutique_secure_jwt_secret_key_2026_x89f',
        'expiry_days' => (int)(getenv('JWT_EXPIRY_DAYS') ?: 90),
    ],
    'defaults' => [
        'profit_margin' => (float)(getenv('DEFAULT_PROFIT_MARGIN') ?: 25.0),
        'currency'      => getenv('DEFAULT_CURRENCY') ?: 'INR',
        'timezone'      => getenv('DEFAULT_TIMEZONE') ?: 'Asia/Kolkata',
    ],
    'app' => [
        'env'   => getenv('APP_ENV') ?: 'production',
        'debug' => filter_var(getenv('DEBUG') ?: false, FILTER_VALIDATE_BOOLEAN),
    ]
];
