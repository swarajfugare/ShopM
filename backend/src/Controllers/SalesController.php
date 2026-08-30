<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Services\FinancialCalculationService;
use Matoshree\Services\AuditService;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;
use Throwable;

class SalesController {
    public static function create(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $userId = (int)$authUser['user_id'];
        $input = Validator::getJsonInput();

        $saleType = strtoupper(Validator::sanitizeString($input['sale_type'] ?? 'DETAILED'));
        if (!in_array($saleType, ['DETAILED', 'QUICK'], true)) {
            Response::error('Invalid sale_type. Must be DETAILED or QUICK.', 422);
        }

        // Unique transaction UUID from client (offline-first idempotency)
        $txUuid = Validator::sanitizeString($input['transaction_uuid'] ?? '');
        if (empty($txUuid)) {
            $txUuid = bin2hex(random_bytes(16));
        }

        $pdo = Database::getConnection();

        // 1. Idempotency Check: return existing bill if transaction_uuid is already present
        $checkStmt = $pdo->prepare("SELECT * FROM bills WHERE shop_id = ? AND transaction_uuid = ? LIMIT 1");
        $checkStmt->execute([$shopId, $txUuid]);
        $existing = $checkStmt->fetch(PDO::FETCH_ASSOC);
        if ($existing) {
            Response::success([
                'bill' => $existing,
                'is_duplicate' => true
            ], 'Transaction already processed (idempotent response)');
            return;
        }

        $customerId = isset($input['customer_id']) && $input['customer_id'] ? (int)$input['customer_id'] : null;
        $paymentMethod = strtoupper(Validator::sanitizeString($input['payment_method'] ?? 'CASH'));
        $note = Validator::sanitizeString($input['note'] ?? '');
        $billDate = Validator::sanitizeString($input['bill_date'] ?? date('Y-m-d H:i:s'));
        $deviceId = Validator::sanitizeString($input['device_id'] ?? 'mobile_device');

        $pdo->beginTransaction();

        try {
            // Generate sequential bill number: MC-{YEAR}-{SEQUENCE}
            $currentYear = date('Y', strtotime($billDate));
            $seqStmt = $pdo->prepare("
                SELECT COUNT(id) + 1 as next_seq
                FROM bills
                WHERE shop_id = ? AND YEAR(bill_date) = ?
            ");
            $seqStmt->execute([$shopId, $currentYear]);
            $nextSeq = (int)$seqStmt->fetchColumn();
            $billNumber = sprintf('MC-%s-%06d', $currentYear, $nextSeq);

            $subtotal = 0.0;
            $totalDiscount = (float)($input['discount_amount'] ?? 0.0);
            $totalCost = 0.0;
            $estimatedProfit = 0.0;
            $actualProfit = 0.0;
            $profitType = 'ESTIMATED';
            $processedItems = [];

            if ($saleType === 'DETAILED') {
                $items = $input['items'] ?? [];
                if (empty($items) || !is_array($items)) {
                    $pdo->rollBack();
                    Response::error('Detailed sale requires at least one product item.', 422);
                }

                foreach ($items as $item) {
                    $productId = isset($item['product_id']) && $item['product_id'] ? (int)$item['product_id'] : null;
                    $productName = Validator::sanitizeString($item['name'] ?? $item['product_name'] ?? 'Custom Boutique Item');
                    $sku = Validator::sanitizeString($item['sku'] ?? '');
                    $categoryId = isset($item['category_id']) && $item['category_id'] ? (int)$item['category_id'] : null;
                    $quantity = max(1, (int)($item['quantity'] ?? 1));
                    $sellingPrice = (float)($item['selling_price'] ?? 0.0);
                    $costPrice = isset($item['cost_price']) && $item['cost_price'] !== null ? (float)$item['cost_price'] : null;
                    $itemDiscount = (float)($item['discount_amount'] ?? 0.0);

                    // Fetch latest product details if productId is supplied
                    if ($productId) {
                        $prodStmt = $pdo->prepare("SELECT * FROM products WHERE id = ? AND shop_id = ? LIMIT 1");
                        $prodStmt->execute([$productId, $shopId]);
                        $prod = $prodStmt->fetch(PDO::FETCH_ASSOC);
                        if ($prod) {
                            $productName = $prod['name'];
                            $sku = $prod['sku'] ?? $sku;
                            $categoryId = $prod['category_id'] ? (int)$prod['category_id'] : $categoryId;
                            if ($sellingPrice <= 0) {
                                $sellingPrice = (float)$prod['selling_price'];
                            }
                            if ($costPrice === null && $prod['cost_price'] !== null) {
                                $costPrice = (float)$prod['cost_price'];
                            }

                            // If tracking stock, adjust inventory
                            if ((int)$prod['track_inventory'] === 1) {
                                $pdo->prepare("UPDATE products SET current_stock = current_stock - ? WHERE id = ?")
                                    ->execute([$quantity, $productId]);
                            }
                        }
                    }

                    $fin = FinancialCalculationService::calculateItemFinancials(
                        $sellingPrice,
                        $quantity,
                        $costPrice,
                        $itemDiscount,
                        25.0
                    );

                    $subtotal += ($sellingPrice * $quantity);
                    $totalCost += $fin['line_cost'];
                    if ($fin['profit_type'] === 'ACTUAL') {
                        $actualProfit += $fin['line_profit'];
                        $profitType = 'ACTUAL';
                    } else {
                        $estimatedProfit += $fin['line_profit'];
                    }

                    $processedItems[] = [
                        'product_id'            => $productId,
                        'product_name_snapshot' => $productName,
                        'sku_snapshot'          => $sku,
                        'category_id'           => $categoryId,
                        'quantity'              => $quantity,
                        'selling_price'         => $sellingPrice,
                        'cost_price'            => $costPrice,
                        'discount_amount'       => $itemDiscount,
                        'line_total'            => $fin['line_total'],
                        'line_cost'             => $fin['line_cost'],
                        'line_profit'           => $fin['line_profit']
                    ];
                }

                $finalAmount = max(0.0, round($subtotal - $totalDiscount, 2));
            } else {
                // QUICK SALE
                $finalAmount = (float)($input['final_amount'] ?? 0.0);
                if ($finalAmount <= 0) {
                    $pdo->rollBack();
                    Response::error('Quick sale final amount must be greater than zero.', 422);
                }

                $qFin = FinancialCalculationService::calculateQuickSaleFinancials($finalAmount, 25.0);
                $subtotal = $qFin['subtotal'];
                $totalCost = $qFin['cost_amount'];
                $estimatedProfit = $qFin['estimated_profit'];
                $actualProfit = 0.0;
                $profitType = 'ESTIMATED';
            }

            // 2. Insert into bills table
            $billInsert = $pdo->prepare("
                INSERT INTO bills (
                    shop_id, customer_id, bill_number, transaction_uuid, sale_type,
                    subtotal, discount_amount, tax_amount, final_amount, cost_amount,
                    estimated_profit, actual_profit, profit_type, payment_method, payment_status,
                    note, bill_date, created_by, device_id, sync_status
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, 'SYNCED'
                )
            ");
            $billInsert->execute([
                $shopId, $customerId, $billNumber, $txUuid, $saleType,
                $subtotal, $totalDiscount, 0.0, $finalAmount, $totalCost,
                $estimatedProfit, $actualProfit, $profitType, $paymentMethod, 'PAID',
                $note, $billDate, $userId, $deviceId
            ]);
            $billId = (int)$pdo->lastInsertId();

            // 3. Insert bill items (with snapshot guarantees)
            if (!empty($processedItems)) {
                $itemStmt = $pdo->prepare("
                    INSERT INTO bill_items (
                        bill_id, product_id, product_name_snapshot, sku_snapshot, category_id,
                        quantity, selling_price, cost_price, discount_amount, line_total, line_cost, line_profit
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ");
                foreach ($processedItems as $it) {
                    $itemStmt->execute([
                        $billId, $it['product_id'], $it['product_name_snapshot'], $it['sku_snapshot'], $it['category_id'],
                        $it['quantity'], $it['selling_price'], $it['cost_price'], $it['discount_amount'],
                        $it['line_total'], $it['line_cost'], $it['line_profit']
                    ]);
                }
            }

            // 4. Record Payment
            $payStmt = $pdo->prepare("
                INSERT INTO payments (bill_id, payment_method, amount, payment_date, note)
                VALUES (?, ?, ?, ?, ?)
            ");
            $payStmt->execute([$billId, $paymentMethod, $finalAmount, $billDate, $note]);

            // 5. Update Customer Lifetime Metrics Transactionally
            if ($customerId) {
                $custStmt = $pdo->prepare("
                    UPDATE customers 
                    SET total_bills = total_bills + 1,
                        lifetime_spend = lifetime_spend + ?,
                        first_purchase_at = COALESCE(first_purchase_at, ?),
                        last_purchase_at = ?
                    WHERE id = ? AND shop_id = ?
                ");
                $custStmt->execute([$finalAmount, $billDate, $billDate, $customerId, $shopId]);
            }

            // 6. Audit Trail
            AuditService::log($pdo, $shopId, $userId, 'BILLS', $billId, 'CREATE', null, [
                'bill_number'  => $billNumber,
                'final_amount' => $finalAmount,
                'sale_type'    => $saleType
            ]);

            $pdo->commit();

            // Return full bill object with items
            $billResult = [
                'id'               => $billId,
                'shop_id'          => $shopId,
                'customer_id'      => $customerId,
                'bill_number'      => $billNumber,
                'transaction_uuid' => $txUuid,
                'sale_type'        => $saleType,
                'subtotal'         => $subtotal,
                'discount_amount'  => $totalDiscount,
                'final_amount'     => $finalAmount,
                'estimated_profit' => $estimatedProfit,
                'actual_profit'    => $actualProfit,
                'profit_type'      => $profitType,
                'payment_method'   => $paymentMethod,
                'payment_status'   => 'PAID',
                'note'             => $note,
                'bill_date'        => $billDate,
                'items'            => $processedItems
            ];

            Response::success(['bill' => $billResult], 'Sale created successfully', 201);
        } catch (Throwable $e) {
            $pdo->rollBack();
            Response::error('Failed to create sale: ' . $e->getMessage(), 500);
        }
    }
}
