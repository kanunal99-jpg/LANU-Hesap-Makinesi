create table if not exists public.lanu_call_signals (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.lanu_conversations(id) on delete cascade,
  sender_id uuid not null references auth.users(id) on delete cascade,
  signal_type text not null check (signal_type in ('offer','answer','ice','hangup','reject','busy')),
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists lanu_call_signals_conversation_created_idx
  on public.lanu_call_signals(conversation_id, created_at);

alter table public.lanu_call_signals enable row level security;

drop policy if exists lanu_call_signals_select_participant on public.lanu_call_signals;
drop policy if exists lanu_call_signals_insert_participant on public.lanu_call_signals;

create policy lanu_call_signals_select_participant
  on public.lanu_call_signals for select
  using (
    exists (
      select 1 from public.lanu_conversations c
      where c.id = conversation_id
        and (auth.uid() = c.participant_a or auth.uid() = c.participant_b)
    )
  );

create policy lanu_call_signals_insert_participant
  on public.lanu_call_signals for insert
  with check (
    auth.uid() = sender_id
    and exists (
      select 1 from public.lanu_conversations c
      where c.id = conversation_id
        and (auth.uid() = c.participant_a or auth.uid() = c.participant_b)
    )
  );

alter publication supabase_realtime add table public.lanu_call_signals;
