package com.eggbusinessmanager.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Integrity rules Room's annotations cannot express (Constitution II, data-model.md § ledger_entry),
 * created with the schema. They are a second line of defence behind the domain rules:
 * - the balance-effect sign must match the entry type (BalanceEffect), and amounts/dates stay in range;
 * - ledger rows are append-only: only status/void/link columns may change, and rows are never deleted;
 * - at most one ACTIVE revision per logical entry (partial unique index).
 * Every future migration that rebuilds `ledger_entry` must recreate these (data-model.md § Migration Policy).
 */
object SchemaCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }

    val STATEMENTS = listOf(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_entry_one_active_revision
        ON ledger_entry (root_entry_id) WHERE status = 'ACTIVE' AND root_entry_id > 0
        """,
        """
        CREATE TRIGGER IF NOT EXISTS ledger_entry_check_insert BEFORE INSERT ON ledger_entry
        WHEN NEW.entry_type NOT IN ('OPENING_BALANCE', 'SELL', 'BUY', 'PAYMENT_RECEIVED', 'PAYMENT_MADE')
          OR NEW.status NOT IN ('ACTIVE', 'SUPERSEDED', 'VOIDED')
          OR NEW.amount_paise < 0 OR NEW.amount_paise > 99999999999
          OR (NEW.amount_paise = 0 AND NEW.entry_type <> 'OPENING_BALANCE')
          OR NEW.entry_date < 10957
          OR (NEW.entry_type = 'OPENING_BALANCE') <> (NEW.opening_direction IS NOT NULL)
          OR NEW.opening_direction NOT IN ('CUSTOMER_OWES', 'SHOP_OWES')
          OR (NEW.entry_type IN ('SELL', 'BUY') AND (
                NEW.variety_id IS NULL OR NEW.unit_id IS NULL OR NEW.quantity_milli IS NULL OR NEW.quantity_milli <= 0
                OR NEW.pieces_per_unit_snapshot IS NULL OR NEW.pieces_per_unit_snapshot < 1
                OR NEW.pieces IS NULL OR NEW.pieces < 1 OR NEW.pieces > 10000000
                OR NEW.quantity_milli * NEW.pieces_per_unit_snapshot <> NEW.pieces * 1000
                OR NEW.price_per_unit_paise IS NULL OR NEW.price_per_unit_paise < 1 OR NEW.price_per_unit_paise > 100000000
                OR NEW.payment_mode IS NOT NULL))
          OR (NEW.entry_type NOT IN ('SELL', 'BUY') AND (
                NEW.variety_id IS NOT NULL OR NEW.unit_id IS NOT NULL OR NEW.quantity_milli IS NOT NULL
                OR NEW.pieces_per_unit_snapshot IS NOT NULL OR NEW.pieces IS NOT NULL OR NEW.price_per_unit_paise IS NOT NULL))
          OR NEW.balance_effect_paise IS NOT CASE
                WHEN NEW.entry_type IN ('SELL', 'PAYMENT_MADE') THEN NEW.amount_paise
                WHEN NEW.entry_type IN ('BUY', 'PAYMENT_RECEIVED') THEN -NEW.amount_paise
                WHEN NEW.opening_direction = 'CUSTOMER_OWES' THEN NEW.amount_paise
                ELSE -NEW.amount_paise
             END
        BEGIN SELECT RAISE(ABORT, 'ledger_entry rule violated'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS ledger_entry_append_only BEFORE UPDATE ON ledger_entry
        WHEN NEW.entry_type IS NOT OLD.entry_type OR NEW.customer_id IS NOT OLD.customer_id
          OR NEW.entry_date IS NOT OLD.entry_date OR NEW.amount_paise IS NOT OLD.amount_paise
          OR NEW.balance_effect_paise IS NOT OLD.balance_effect_paise OR NEW.reference_no IS NOT OLD.reference_no
          OR NEW.opening_direction IS NOT OLD.opening_direction OR NEW.created_at IS NOT OLD.created_at
          OR NEW.variety_id IS NOT OLD.variety_id OR NEW.unit_id IS NOT OLD.unit_id
          OR NEW.quantity_milli IS NOT OLD.quantity_milli OR NEW.pieces_per_unit_snapshot IS NOT OLD.pieces_per_unit_snapshot
          OR NEW.pieces IS NOT OLD.pieces OR NEW.price_per_unit_paise IS NOT OLD.price_per_unit_paise
          OR NEW.remarks IS NOT OLD.remarks OR NEW.client_request_id IS NOT OLD.client_request_id
          OR (OLD.status <> 'ACTIVE' AND NEW.status IS NOT OLD.status)
          OR (OLD.root_entry_id > 0 AND NEW.root_entry_id IS NOT OLD.root_entry_id)
        BEGIN SELECT RAISE(ABORT, 'ledger_entry rows are append-only'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS ledger_entry_no_delete BEFORE DELETE ON ledger_entry
        BEGIN SELECT RAISE(ABORT, 'ledger_entry rows are never deleted'); END
        """,
        "INSERT OR IGNORE INTO reference_sequence (entry_type, next_value) VALUES ('OPENING_BALANCE', 1)",
        "INSERT OR IGNORE INTO reference_sequence (entry_type, next_value) VALUES ('SELL', 1)",
        "INSERT OR IGNORE INTO reference_sequence (entry_type, next_value) VALUES ('BUY', 1)",
        "INSERT OR IGNORE INTO reference_sequence (entry_type, next_value) VALUES ('PAYMENT_RECEIVED', 1)",
        "INSERT OR IGNORE INTO reference_sequence (entry_type, next_value) VALUES ('PAYMENT_MADE', 1)",
        // Default units (FR-059): each with its own conversion to eggs. Piece is the locked base unit.
        unit("Piece", 1, system = true, order = 0),
        unit("Dozen", 12, system = false, order = 1),
        unit("Tray", 30, system = false, order = 2),
        unit("Box", 210, system = false, order = 3),
        // Default varieties (FR-027a), White is the default.
        variety("White", default = true, order = 0),
        variety("Brown", default = false, order = 1),
        variety("Country (Desi)", default = false, order = 2),
    ).map { it.trimIndent() }

    private fun unit(name: String, pieces: Int, system: Boolean, order: Int) =
        "INSERT OR IGNORE INTO measurement_unit (name, name_normalized, pieces_per_unit, is_active, is_system, sort_order) " +
            "VALUES ('$name', '${name.lowercase()}', $pieces, 1, ${if (system) 1 else 0}, $order)"

    private fun variety(name: String, default: Boolean, order: Int) =
        "INSERT OR IGNORE INTO egg_variety (name, name_normalized, is_active, is_default, sort_order) " +
            "VALUES ('$name', '${name.lowercase()}', 1, ${if (default) 1 else 0}, $order)"
}
