INSERT INTO settlement_stream VALUES ('10000000-0000-0000-0000-000000000001', 6);
INSERT INTO append_batch VALUES (1, 6, '10000000-0000-0000-0000-000000000001');
INSERT INTO settlement_event
    (position, batch_position, stream_id, sequence, event_id, occurred_second, occurred_nano, event_type, schema_version, payload)
VALUES
    (1, 1, '10000000-0000-0000-0000-000000000001', 1, '20000000-0000-0000-0000-000000000001', 1791072000, 123456789, 'SettlementOpened', 1,
     '{"name":"Mountain weekend","currency":"EUR"}'),
    (2, 1, '10000000-0000-0000-0000-000000000001', 2, '20000000-0000-0000-0000-000000000002', 1791072001, 123456789, 'ParticipantAdded', 1,
     '{"participantId":"00000000-0000-0000-0000-000000000001","name":"Amy"}'),
    (3, 1, '10000000-0000-0000-0000-000000000001', 3, '20000000-0000-0000-0000-000000000003', 1791072002, 123456789, 'ParticipantAdded', 1,
     '{"participantId":"00000000-0000-0000-0000-000000000002","name":"Bob"}'),
    (4, 1, '10000000-0000-0000-0000-000000000001', 4, '20000000-0000-0000-0000-000000000004', 1791072003, 123456789, 'ExchangeRateConfigured', 1,
     '{"version":{"id":"00000000-0000-0000-0000-000000000005","definedAt":"2026-10-04T00:00","rate":{"source":"USD","target":"EUR","value":"0.9"},"from":null,"to":null}}'),
    (5, 1, '10000000-0000-0000-0000-000000000001', 5, '20000000-0000-0000-0000-000000000005', 1791072004, 123456789, 'ExpenseRecorded', 1,
     '{"expenseId":"00000000-0000-0000-0000-000000000003","description":"Dinner","incurredOn":"2026-10-04","payer":"00000000-0000-0000-0000-000000000001","originalAmount":{"amount":"100","currency":"USD"},"allocation":{"kind":"equal","entries":[{"participantId":"00000000-0000-0000-0000-000000000001"},{"participantId":"00000000-0000-0000-0000-000000000002"}]},"exchangeRateOverride":null,"componentVersionId":"00000000-0000-0000-0000-000000000005","exchangeRate":{"source":"USD","target":"EUR","value":"0.9"},"valuation":{"amount":"90.00","currency":"EUR"},"shares":[{"participantId":"00000000-0000-0000-0000-000000000001","amount":{"amount":"45.00","currency":"EUR"}},{"participantId":"00000000-0000-0000-0000-000000000002","amount":{"amount":"45.00","currency":"EUR"}}]}'),
    (6, 1, '10000000-0000-0000-0000-000000000001', 6, '20000000-0000-0000-0000-000000000006', 1791072005, 123456789, 'RepaymentRecorded', 1,
     '{"repaymentId":"00000000-0000-0000-0000-000000000004","paidOn":"2026-10-04","payer":"00000000-0000-0000-0000-000000000002","recipient":"00000000-0000-0000-0000-000000000001","amount":{"amount":"10.00","currency":"EUR"}}');
UPDATE event_cursor SET position = 6;
