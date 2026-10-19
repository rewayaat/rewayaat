// The bookcase (fragments/bookshelf.html). A touch screen has no hover, so the first tap
// takes a book from the shelf and lights it, and the second follows the link.
(function () {
    if (!window.matchMedia('(hover: none) and (pointer: coarse)').matches) {
        return;
    }
    document.addEventListener('click', function (event) {
        var book = event.target.closest('.bookcase .shelf-book');
        if (!book || book.classList.contains('is-open')) {
            return;
        }
        event.preventDefault();
        document.querySelectorAll('.bookcase .shelf-book.is-open').forEach(function (other) {
            other.classList.remove('is-open');
        });
        book.classList.add('is-open');
    });
})();
