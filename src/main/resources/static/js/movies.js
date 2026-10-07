/*
 * 영화 추천 화면
 *  - 의미 검색과 AI 선택에 몇 초 걸릴 수 있어서, 제출하면 버튼을 잠그고 진행 중임을 알려 준다
 *    (뒤로 가기로 돌아왔을 때 잠긴 채 남지 않게 pageshow 에서 되돌린다)
 *  - 관리자 색인이 진행 중이면 3초마다 상태를 조회해 진행 문구를 갱신한다
 */
(function () {
  'use strict';

  var form = document.getElementById('movie-form');
  var button = document.getElementById('movie-btn');
  if (form && button) {
    var label = button.querySelector('.label-text');
    var original = label.textContent;
    var modes = form.querySelectorAll('input[name="mode"]');
    function updateChipMode(mode) {
      form.querySelectorAll('.chips a[href]').forEach(function (chip) {
        var url = new URL(chip.href, window.location.href);
        url.searchParams.set('mode', mode);
        chip.href = url.pathname + url.search + url.hash;
      });
      form.querySelectorAll('.movie-search-chips').forEach(function (chips) {
        chips.hidden = chips.dataset.mode !== mode;
      });
    }
    var selectedMode = form.querySelector('input[name="mode"]:checked');
    if (selectedMode) {
      updateChipMode(selectedMode.value);
    }
    modes.forEach(function (radio) {
      radio.addEventListener('change', function () {
        modes.forEach(function (item) {
          item.closest('.movie-search-mode').classList.toggle('selected', item.checked);
        });
        label.textContent = radio.value === 'fast' ? '빠르게 찾기' : 'AI에게 추천받기';
        form.querySelector('input[name="q"]').placeholder = radio.value === 'fast'
          ? '작품, 배우, 감독을 검색해보세요'
          : '예: 비 오는 날 혼자 보기 좋은 잔잔한 영화';
        updateChipMode(radio.value);
      });
    });
    form.addEventListener('submit', function () {
      button.disabled = true;
      label.textContent = '검색 중...';
      var loadingBox = document.getElementById('ai-loading-box');
      var loadingText = document.getElementById('ai-loading-text');
      if (loadingBox && loadingText) {
          loadingBox.hidden = false;
          var q = form.querySelector('input[name="q"]').value || '';
          if (q.includes('비슷한') || q.includes('같은')) {
              loadingText.textContent = 'AI가 전 세계 명작 중 결이 비슷한 작품을 찾는 중입니다 🍿 (약 5~8초)';
          } else {
              loadingText.textContent = 'AI 큐레이터가 열심히 엄선하고 있습니다 🍿 (약 5초)';
          }
      }
    });
    window.addEventListener('pageshow', function () {
      button.disabled = false;
      label.textContent = original;
      var loadingBox = document.getElementById('ai-loading-box');
      if (loadingBox) loadingBox.hidden = true;
    });
  }

  // 빠른 검색 결과는 처음 10편만 보여주고, 사용자가 원할 때 10편씩 더 펼친다.
  var movieList = document.querySelector('.movie-list[data-mode="fast"]');
  var movieMore = document.getElementById('movie-list-more');
  if (movieList && movieMore) {
    var movieCards = Array.prototype.slice.call(movieList.querySelectorAll('.movie-card'));
    var shownMovies = 10;
    function updateMoreLabel() {
      var remaining = movieCards.length - shownMovies;
      movieMore.textContent = remaining > 0 ? '더보기 (' + remaining + '편 남음)' : '더보기';
    }
    movieCards.forEach(function (card, index) { card.hidden = index >= shownMovies; });
    updateMoreLabel();
    movieMore.addEventListener('click', function () {
      shownMovies = Math.min(shownMovies + 10, movieCards.length);
      movieCards.forEach(function (card, index) { card.hidden = index >= shownMovies; });
      updateMoreLabel();
      if (shownMovies >= movieCards.length) {
        movieMore.hidden = true;
      }
    });
  }

  // 인기/상영작 포스터 줄: 제목 오른쪽에 ‹ › 버튼을 달고, 양끝 흐림 효과를 스크롤 위치에 맞춰 켜고 끈다
  document.querySelectorAll('.home-section').forEach(function (section) {
    var row = section.querySelector('.poster-row');
    var title = section.querySelector('.home-title');
    if (!row || !title) {
      return;
    }
    var head = document.createElement('div');
    head.className = 'home-head';
    var nav = document.createElement('div');
    nav.className = 'row-nav';
    function makeButton(symbol, label, direction) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'row-btn';
      b.textContent = symbol;
      b.setAttribute('aria-label', label);
      b.addEventListener('click', function () {
        row.scrollBy({ left: direction * row.clientWidth * 0.85, behavior: 'smooth' });
      });
      return b;
    }
    var prev = makeButton('‹', '이전', -1);
    var next = makeButton('›', '다음', 1);
    nav.append(prev, next);
    title.replaceWith(head);
    head.append(title, nav);

    function update() {
      var atStart = row.scrollLeft <= 2;
      var atEnd = row.scrollLeft + row.clientWidth >= row.scrollWidth - 2;
      row.classList.toggle('at-start', atStart);
      row.classList.toggle('at-end', atEnd);
      prev.disabled = atStart;
      next.disabled = atEnd;
    }
    row.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    update();
  });

  // 줄거리가 3줄을 넘어 잘린 카드에만 "줄거리 더보기"를 보여주고, 누르면 펼치고 접는다
  document.querySelectorAll('.movie-body .overview').forEach(function (overview) {
    var button = overview.nextElementSibling;
    if (!button || !button.classList.contains('more-btn')) {
      return;
    }
    if (overview.scrollHeight > overview.clientHeight + 1) {
      button.hidden = false;
    }
    button.addEventListener('click', function () {
      var expanded = overview.classList.toggle('expanded');
      button.textContent = expanded ? '접기' : '줄거리 더보기';
    });
  });

  var statusEl = document.getElementById('index-status');
  if (!statusEl) {
    return;
  }

  var statusUrl = statusEl.dataset.statusUrl || '/movies/index-status';
  function poll() {
    fetch(statusUrl, { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (s) {
        if (!s) {
          return;
        }
        statusEl.textContent = s.message || '';
        if (s.running) {
          window.setTimeout(poll, 3000);
        }
      })
      .catch(function () { /* 다음 새로고침에서 다시 확인 */ });
  }

  if (statusEl.dataset.running === 'true') {
    poll();
  }
})();
