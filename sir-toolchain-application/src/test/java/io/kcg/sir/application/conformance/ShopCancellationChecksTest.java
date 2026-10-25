package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SQL evidence roles, not a replacement for real JDBC/rollback checks. */
class ShopCancellationChecksTest {
    @ParameterizedTest
    @CsvSource(delimiter='|',value={
        "SELECT @@SESSION.transaction_read_only|session",
        "SELECT id, owner_id FROM `access_token` WHERE id = ?|access_token",
        "SELECT id FROM user WHERE id=?|user",
        "SELECT id FROM purchase_order WHERE id=? FOR UPDATE|purchase_order",
        "SELECT COUNT(*) FROM order_line WHERE purchase_id=?|order_line",
        "SELECT id, product_id FROM order_line ORDER BY product_id ASC, id ASC LIMIT ?|order_line",
        "SELECT id, stock FROM shop_product WHERE id=? FOR UPDATE|shop_product",
        "SELECT id, coupon_id FROM redemption WHERE purchase_id=? ORDER BY id ASC LIMIT ?|redemption",
        "SELECT id, used FROM coupon WHERE id=? FOR UPDATE|coupon",
        "SELECT id FROM private_data|unexpected",
        "SELECT @@SESSION.unregistered_probe|unexpected",
        "SELECT id FROM order_line JOIN private_data ON private_data.id=order_line.id|unexpected",
        "SELECT id FROM user UNION SELECT id FROM private_data|unexpected",
        "SELECT (SELECT secret FROM private_data) FROM shop_product|unexpected"
    })
    void everyReadMustHaveAKnownRoleWithoutHidingOtherReads(String sql,String expected) {
        assertEquals(expected,ShopCancellationChecks.classify(sql));
    }
}
