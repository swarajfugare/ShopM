<?php
declare(strict_types=1);

namespace Matoshree\Middleware;

use Matoshree\Utils\JWT;
use Matoshree\Utils\Response;
use Matoshree\Config\Database;
use PDO;

class AuthMiddleware {
    public static function authenticate(): array {
        $headers = getallheaders();
        $authHeader = $headers['Authorization'] ?? $headers['authorization'] ?? $_SERVER['HTTP_AUTHORIZATION'] ?? '';

        if (empty($authHeader) || !preg_match('/Bearer\s+(.+)/i', $authHeader, $matches)) {
            Response::unauthorized('Authentication token missing. Please provide Authorization: Bearer <token>');
        }

        $token = $matches[1];
        $config = require dirname(__DIR__, 2) . '/config/config.php';
        $secret = $config['jwt']['secret'];

        $payload = JWT::decode($token, $secret);
        if (!$payload || empty($payload['user_id']) || empty($payload['shop_id'])) {
            Response::unauthorized('Invalid or expired authentication token. Please log in again.');
        }

        // Validate user and shop active state in database
        try {
            $pdo = Database::getConnection();
            $stmt = $pdo->prepare("
                SELECT u.id as user_id, u.shop_id, u.name, u.mobile, u.email, u.role, u.is_active as user_active,
                       s.name as shop_name, s.currency, s.is_active as shop_active
                FROM users u
                JOIN shops s ON s.id = u.shop_id
                WHERE u.id = ? AND u.shop_id = ?
                LIMIT 1
            ");
            $stmt->execute([$payload['user_id'], $payload['shop_id']]);
            $user = $stmt->fetch(PDO::FETCH_ASSOC);

            if (!$user || (int)$user['user_active'] !== 1 || (int)$user['shop_active'] !== 1) {
                Response::unauthorized('Account or shop is inactive. Please contact administrator.');
            }

            return $user;
        } catch (\Throwable $e) {
            Response::error('Authentication verification error: ' . $e->getMessage(), 500);
            exit;
        }
    }
}
