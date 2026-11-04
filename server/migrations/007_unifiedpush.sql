-- UnifiedPush (Web Push) devices, for builds without Google services (F-Droid). The token is the distributor's
-- endpoint URL; p256dh and auth are the app's Web Push keys, so the distributor only relays ciphertext.

alter table device_tokens drop constraint device_tokens_platform_check;
alter table device_tokens add constraint device_tokens_platform_check check (platform in ('ios', 'android', 'unifiedpush'));
alter table device_tokens add column p256dh text;
alter table device_tokens add column auth text;
