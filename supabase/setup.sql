-- Dailies: friends and leaderboards, stage 1 (accounts, display names, friend codes, friends).
--
-- Run this once in your Supabase project: Dashboard → SQL Editor → New query → paste → Run.
-- It's safe to run again (after an update to this file, run the whole file again).
--
-- How it's kept private:
--  - The app never reads or writes the tables directly. Every table has row-level security on and no
--    access for the app's roles; the app calls the functions below, which only ever act for the person
--    signed in (auth.uid()).
--  - Nobody can list or search other players. You find a friend only by their 8-character friend code,
--    and they have to accept before either of you sees the other's name.
--  - A new account's display name is "Player" until it's changed: nothing from the email is shown.

-- ---------------------------------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------------------------------
create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null default 'Player' check (char_length(display_name) between 1 and 30),
  -- 8 characters without the ones that look alike (no 0/O, 1/I).
  friend_code text not null unique check (friend_code ~ '^[2-9A-HJ-NP-Z]{8}$'),
  created_at timestamptz not null default now()
);

create table if not exists public.friendships (
  requester uuid not null references public.profiles (id) on delete cascade,
  addressee uuid not null references public.profiles (id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted')),
  created_at timestamptz not null default now(),
  accepted_at timestamptz,
  primary key (requester, addressee),
  check (requester <> addressee)
);
-- One row per pair, whichever of the two asked.
create unique index if not exists friendships_one_per_pair
  on public.friendships (least(requester, addressee), greatest(requester, addressee));
create index if not exists friendships_addressee on public.friendships (addressee);

alter table public.profiles enable row level security;
alter table public.friendships enable row level security;
-- No direct access for the app: everything goes through the functions below.
revoke all on public.profiles from anon, authenticated;
revoke all on public.friendships from anon, authenticated;

-- ---------------------------------------------------------------------------------------------------
-- Helpers (not callable by the app)
-- ---------------------------------------------------------------------------------------------------
-- A friend code no one has, from random bytes (a version-4 UUID's random part).
create or replace function public.new_friend_code()
returns text
language plpgsql
volatile
set search_path = ''
as $$
declare
  alphabet constant text := '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
  bytes bytea;
  code text;
  i int;
begin
  loop
    bytes := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
    code := '';
    -- Bytes 0-5 and 9-15 are random in a v4 UUID (6 and 8 carry the version and variant).
    foreach i in array array[0, 1, 2, 3, 4, 5, 9, 10] loop
      code := code || substr(alphabet, 1 + (get_byte(bytes, i) % 32), 1);
    end loop;
    exit when not exists (select 1 from public.profiles where friend_code = code);
  end loop;
  return code;
end;
$$;

-- Every new account gets a profile.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, friend_code) values (new.id, public.new_friend_code())
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- The signed-in person's id, or an error if no one is signed in.
create or replace function public.require_user()
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
  me uuid := auth.uid();
begin
  if me is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;
  return me;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- What the app calls
-- ---------------------------------------------------------------------------------------------------
-- Your own profile (made now if the account is older than this setup).
create or replace function public.my_profile()
returns table (display_name text, friend_code text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := public.require_user();
begin
  insert into public.profiles (id, friend_code) values (me, public.new_friend_code())
  on conflict (id) do nothing;
  return query select p.display_name, p.friend_code from public.profiles p where p.id = me;
end;
$$;

-- Changes your display name (1 to 30 characters, spaces trimmed).
create or replace function public.set_display_name(new_name text)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := public.require_user();
  clean text := btrim(regexp_replace(coalesce(new_name, ''), '\s+', ' ', 'g'));
begin
  if char_length(clean) < 1 or char_length(clean) > 30 then
    raise exception 'name must be 1 to 30 characters' using errcode = '22023';
  end if;
  update public.profiles set display_name = clean where id = me;
  return clean;
end;
$$;

-- Asks the player with this friend code to be friends. Returns:
--   'sent'      a request is waiting for them
--   'accepted'  they had already asked you, so you're friends now
--   'already'   you're already friends, or your request is still waiting
--   'self'      that's your own code
--   'not_found' no one has that code
--   'too_many'  you have too many requests waiting (50)
create or replace function public.send_friend_request(code text)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := public.require_user();
  them uuid;
  existing public.friendships;
begin
  select p.id into them from public.profiles p
    where p.friend_code = upper(regexp_replace(coalesce(code, ''), '[^0-9A-Za-z]', '', 'g'));
  if them is null then return 'not_found'; end if;
  if them = me then return 'self'; end if;
  select * into existing from public.friendships f
    where (f.requester = me and f.addressee = them) or (f.requester = them and f.addressee = me);
  if found then
    if existing.status = 'pending' and existing.requester = them then
      update public.friendships set status = 'accepted', accepted_at = now()
        where requester = them and addressee = me;
      return 'accepted';
    end if;
    return 'already';
  end if;
  if (select count(*) from public.friendships f where f.requester = me and f.status = 'pending') >= 50 then
    return 'too_many';
  end if;
  insert into public.friendships (requester, addressee) values (me, them);
  return 'sent';
end;
$$;

-- Accepts or declines a request someone sent you.
create or replace function public.respond_friend_request(other uuid, accept boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := public.require_user();
begin
  if accept then
    update public.friendships set status = 'accepted', accepted_at = now()
      where requester = other and addressee = me and status = 'pending';
  else
    delete from public.friendships where requester = other and addressee = me and status = 'pending';
  end if;
end;
$$;

-- Removes a friend, or takes back a request you sent.
create or replace function public.remove_friend(other uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := public.require_user();
begin
  delete from public.friendships
    where (requester = me and addressee = other) or (requester = other and addressee = me);
end;
$$;

-- Your friends and requests. Until a request is accepted, the person who sent it sees only the code they
-- typed, not the other player's name.
--   status 'accepted' = friends; 'incoming' = they asked you; 'outgoing' = you asked them.
create or replace function public.list_friends()
returns table (id uuid, display_name text, status text, since timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := public.require_user();
begin
  return query
    select p.id,
           case when f.status = 'pending' and f.requester = me then p.friend_code else p.display_name end,
           case when f.status = 'accepted' then 'accepted' when f.requester = me then 'outgoing' else 'incoming' end,
           coalesce(f.accepted_at, f.created_at)
      from public.friendships f
      join public.profiles p on p.id = case when f.requester = me then f.addressee else f.requester end
     where f.requester = me or f.addressee = me
     order by 3, 2;
end;
$$;

-- Deletes your account and everything kept for it (profile, friends, requests).
create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := public.require_user();
begin
  delete from auth.users where id = me;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- Who may call what: signed-in players the functions above; no one the helpers.
-- ---------------------------------------------------------------------------------------------------
revoke all on function public.new_friend_code() from public, anon, authenticated;
revoke all on function public.handle_new_user() from public, anon, authenticated;
revoke all on function public.require_user() from public, anon, authenticated;
revoke all on function public.my_profile() from public, anon;
revoke all on function public.set_display_name(text) from public, anon;
revoke all on function public.send_friend_request(text) from public, anon;
revoke all on function public.respond_friend_request(uuid, boolean) from public, anon;
revoke all on function public.remove_friend(uuid) from public, anon;
revoke all on function public.list_friends() from public, anon;
revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.my_profile() to authenticated;
grant execute on function public.set_display_name(text) to authenticated;
grant execute on function public.send_friend_request(text) to authenticated;
grant execute on function public.respond_friend_request(uuid, boolean) to authenticated;
grant execute on function public.remove_friend(uuid) to authenticated;
grant execute on function public.list_friends() to authenticated;
grant execute on function public.delete_my_account() to authenticated;
