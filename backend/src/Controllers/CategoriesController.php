<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;

class CategoriesController {
    public static function index(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $pdo = Database::getConnection();

        $stmt = $pdo->prepare("
            SELECT c.*, COUNT(p.id) as product_count
            FROM categories c
            LEFT JOIN products p ON p.category_id = c.id AND p.is_active = 1
            WHERE c.shop_id = ? AND c.is_active = 1
            GROUP BY c.id
            ORDER BY c.name ASC
        ");
        $stmt->execute([$shopId]);
        $categories = $stmt->fetchAll(PDO::FETCH_ASSOC);

        Response::success(['categories' => $categories], 'Categories retrieved');
    }

    public static function create(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $input = Validator::getJsonInput();
        $name = Validator::sanitizeString($input['name'] ?? '');
        $desc = Validator::sanitizeString($input['description'] ?? '');

        if (empty($name)) {
            Response::error('Category name is required.', 422);
        }

        $pdo = Database::getConnection();
        $stmt = $pdo->prepare("INSERT INTO categories (shop_id, name, description) VALUES (?, ?, ?)");
        $stmt->execute([$shopId, $name, $desc]);
        $newId = (int)$pdo->lastInsertId();

        Response::success(['id' => $newId, 'name' => $name, 'description' => $desc], 'Category created', 201);
    }
}
