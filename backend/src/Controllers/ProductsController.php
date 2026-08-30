<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Services\AuditService;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;

class ProductsController {
    public static function index(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $search = trim($_GET['search'] ?? '');
        $categoryId = isset($_GET['category_id']) && $_GET['category_id'] !== '' ? (int)$_GET['category_id'] : null;

        $pdo = Database::getConnection();
        $sql = "
            SELECT p.*, c.name as category_name
            FROM products p
            LEFT JOIN categories c ON c.id = p.category_id
            WHERE p.shop_id = :shop_id AND p.is_active = 1
        ";
        $params = [':shop_id' => $shopId];

        if (!empty($search)) {
            $sql .= " AND (p.name LIKE :search OR p.sku LIKE :search)";
            $params[':search'] = "%{$search}%";
        }

        if ($categoryId) {
            $sql .= " AND p.category_id = :cat_id";
            $params[':cat_id'] = $categoryId;
        }

        $sql .= " ORDER BY p.name ASC";

        $stmt = $pdo->prepare($sql);
        $stmt->execute($params);
        $products = $stmt->fetchAll(PDO::FETCH_ASSOC);

        Response::success(['products' => $products], 'Products retrieved');
    }

    public static function show(array $authUser, int $id): void {
        $shopId = (int)$authUser['shop_id'];
        $pdo = Database::getConnection();

        $stmt = $pdo->prepare("
            SELECT p.*, c.name as category_name
            FROM products p
            LEFT JOIN categories c ON c.id = p.category_id
            WHERE p.id = ? AND p.shop_id = ?
            LIMIT 1
        ");
        $stmt->execute([$id, $shopId]);
        $product = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$product) {
            Response::notFound('Product not found');
        }

        Response::success(['product' => $product], 'Product details retrieved');
    }

    public static function create(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();

        $name = Validator::sanitizeString($input['name'] ?? '');
        $sku = Validator::sanitizeString($input['sku'] ?? '');
        $categoryId = isset($input['category_id']) && $input['category_id'] ? (int)$input['category_id'] : null;
        $sellingPrice = (float)($input['selling_price'] ?? 0.0);
        $costPrice = isset($input['cost_price']) && $input['cost_price'] !== '' ? (float)$input['cost_price'] : null;
        $trackInventory = isset($input['track_inventory']) && filter_var($input['track_inventory'], FILTER_VALIDATE_BOOLEAN) ? 1 : 0;
        $currentStock = (int)($input['current_stock'] ?? 0);
        $imageUrl = Validator::sanitizeString($input['image_url'] ?? '');

        if (empty($name) || $sellingPrice <= 0) {
            Response::error('Product name and a valid selling price (> 0) are required.', 422);
        }

        $pdo = Database::getConnection();
        $stmt = $pdo->prepare("
            INSERT INTO products (
                shop_id, category_id, name, sku, selling_price, cost_price,
                track_inventory, current_stock, image_url, is_active
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
        ");
        $stmt->execute([
            $shopId, $categoryId, $name, $sku, $sellingPrice, $costPrice,
            $trackInventory, $currentStock, $imageUrl ?: null
        ]);
        $newId = (int)$pdo->lastInsertId();

        AuditService::log($pdo, $shopId, $userId, 'PRODUCTS', $newId, 'CREATE', null, ['name' => $name, 'price' => $sellingPrice]);

        self::show($authUser, $newId);
    }

    public static function update(array $authUser, int $id): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();

        $name = Validator::sanitizeString($input['name'] ?? '');
        $sku = Validator::sanitizeString($input['sku'] ?? '');
        $categoryId = isset($input['category_id']) && $input['category_id'] ? (int)$input['category_id'] : null;
        $sellingPrice = (float)($input['selling_price'] ?? 0.0);
        $costPrice = isset($input['cost_price']) && $input['cost_price'] !== '' ? (float)$input['cost_price'] : null;
        $trackInventory = isset($input['track_inventory']) && filter_var($input['track_inventory'], FILTER_VALIDATE_BOOLEAN) ? 1 : 0;
        $currentStock = (int)($input['current_stock'] ?? 0);
        $imageUrl = Validator::sanitizeString($input['image_url'] ?? '');

        if (empty($name) || $sellingPrice <= 0) {
            Response::error('Product name and a valid selling price are required.', 422);
        }

        $pdo = Database::getConnection();
        $stmt = $pdo->prepare("
            UPDATE products
            SET name = ?, sku = ?, category_id = ?, selling_price = ?, cost_price = ?,
                track_inventory = ?, current_stock = ?, image_url = ?
            WHERE id = ? AND shop_id = ?
        ");
        $stmt->execute([
            $name, $sku, $categoryId, $sellingPrice, $costPrice,
            $trackInventory, $currentStock, $imageUrl ?: null, $id, $shopId
        ]);

        AuditService::log($pdo, $shopId, $userId, 'PRODUCTS', $id, 'UPDATE', null, ['name' => $name, 'price' => $sellingPrice]);

        self::show($authUser, $id);
    }
}
