/**
 * cmdk.js — Cmd+K / Ctrl+K command palette
 *
 * Vanilla JS, no frameworks.
 * Opens a <dialog id="cmdk-palette"> element injected by layout.clj.
 * Debounces input → fetches /api/search?q=... → renders results list.
 * Keyboard: ↑↓ to navigate, Enter to open, ESC closes (native dialog).
 * All user-facing strings are HTML-escaped via esc() to prevent XSS.
 */
(function () {
  "use strict";

  var selected = 0;
  var results = [];

  var dialog = document.getElementById("cmdk-palette");
  if (!dialog) return;

  var input = dialog.querySelector("input");
  var list = dialog.querySelector(".cmdk-results");
  if (!input || !list) return;

  // ---------------------------------------------------------------------------
  // Open on Cmd+K (macOS) or Ctrl+K (Linux/Windows)
  // ---------------------------------------------------------------------------
  document.addEventListener("keydown", function (e) {
    if ((e.metaKey || e.ctrlKey) && e.key === "k") {
      e.preventDefault();
      results = [];
      selected = 0;
      list.innerHTML = "";
      input.value = "";
      dialog.showModal();
      input.focus();
    }
  });

  // ---------------------------------------------------------------------------
  // Debounced search input
  // ---------------------------------------------------------------------------
  var debounceTimer;
  input.addEventListener("input", function (e) {
    clearTimeout(debounceTimer);
    var q = e.target.value;
    debounceTimer = setTimeout(function () {
      fetchResults(q);
    }, 200);
  });

  // ---------------------------------------------------------------------------
  // Fetch results from /api/search
  // ---------------------------------------------------------------------------
  function fetchResults(q) {
    if (!q || q.length < 2) {
      results = [];
      selected = 0;
      render();
      return;
    }
    fetch("/api/search?q=" + encodeURIComponent(q))
      .then(function (resp) {
        return resp.json();
      })
      .then(function (data) {
        results = data.results || [];
        selected = 0;
        render();
      })
      .catch(function (err) {
        console.error("cmdk search error:", err);
      });
  }

  // ---------------------------------------------------------------------------
  // HTML escape — same XSS pattern as drill-panel.js / sku-sheet.js
  // ---------------------------------------------------------------------------
  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  // ---------------------------------------------------------------------------
  // Render results list
  // ---------------------------------------------------------------------------
  function icon(type) {
    if (type === "sku") return "👕";
    if (type === "report") return "📊";
    return "🏠";
  }

  function render() {
    if (results.length === 0) {
      list.innerHTML = "";
      return;
    }
    list.innerHTML = results
      .map(function (r, i) {
        var highlight = i === selected ? "background:#dbeafe;" : "";
        return (
          '<li style="display:flex;align-items:center;padding:0.5rem 0.75rem;cursor:pointer;' +
          highlight +
          '" data-idx="' +
          i +
          '" data-route="' +
          esc(r.route) +
          '">' +
          '<span style="margin-right:0.5rem;font-size:1rem">' +
          icon(r.type) +
          "</span>" +
          '<span style="font-weight:500;flex:1">' +
          esc(r.title) +
          "</span>" +
          (r.hint
            ? '<span style="font-size:0.75rem;color:#6b7280;margin-left:0.5rem">' +
              esc(r.hint) +
              "</span>"
            : "") +
          "</li>"
        );
      })
      .join("");
  }

  // ---------------------------------------------------------------------------
  // Keyboard navigation inside the input
  // ---------------------------------------------------------------------------
  input.addEventListener("keydown", function (e) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      selected = results.length > 0
        ? Math.min(selected + 1, results.length - 1)
        : 0;
      render();
      scrollSelected();
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      selected = Math.max(selected - 1, 0);
      render();
      scrollSelected();
    } else if (e.key === "Enter") {
      e.preventDefault();
      var r = results[selected];
      if (r) {
        dialog.close();
        window.location.href = r.route;
      }
    }
    // ESC is handled natively by the <dialog> element
  });

  // ---------------------------------------------------------------------------
  // Scroll the selected item into view
  // ---------------------------------------------------------------------------
  function scrollSelected() {
    var li = list.querySelector("li[data-idx='" + selected + "']");
    if (li) li.scrollIntoView({ block: "nearest" });
  }

  // ---------------------------------------------------------------------------
  // Mouse click on a result
  // ---------------------------------------------------------------------------
  list.addEventListener("click", function (e) {
    var li = e.target.closest("li[data-route]");
    if (li) {
      dialog.close();
      window.location.href = li.dataset.route;
    }
  });

  // ---------------------------------------------------------------------------
  // Hover → update selection highlight
  // ---------------------------------------------------------------------------
  list.addEventListener("mouseover", function (e) {
    var li = e.target.closest("li[data-idx]");
    if (li) {
      selected = parseInt(li.dataset.idx, 10);
      render();
    }
  });
})();
