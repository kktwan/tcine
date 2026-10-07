/*
 * 로그인 화면
 *  - 안내 문구(로그아웃/가입 완료/오류)는 주소의 ?logout 같은 값으로 뜨므로, 한 번 보여준 뒤 주소에서 지운다
 *    (안 지우면 새로고침할 때마다 같은 안내가 계속 나온다)
 *  - Enter로 제출할 때 버튼 클릭(Shift/Ctrl 조합 시 새 창)을 거치지 않고 같은 창에서 바로 제출
 */
(function () {
  'use strict';

  if (window.location.search) {
    window.history.replaceState(null, '', window.location.pathname);
  }

  var form = document.querySelector('form');
  if (form) {
    form.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && e.target.tagName === 'INPUT') {
        e.preventDefault();
        form.requestSubmit();
      }
    });
  }
})();
