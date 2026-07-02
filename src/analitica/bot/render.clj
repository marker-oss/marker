(ns analitica.bot.render
  "Telegram message renderer: DigestSource → plain-text/Markdown sendMessage body.
   Applies gate decisions (analitica.bot.digest) and metric selection (from subscription).
   Implementation deferred to US2/US3 tasks (T023, T031, T032).")
