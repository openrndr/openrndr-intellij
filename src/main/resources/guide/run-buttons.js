/*
 * Injected into guide.openrndr.org pages by the OPENRNDR plugin (OpenrndrGuideBrowser): a green play button next to
 * the copy button of each Kotlin code block that has a main function. The plugin fills in the body of runProgram
 * with a call into the IDE that receives the block's position on the page (from 1), the page title and the code,
 * one per line.
 */
(function () {
    /* The page may finish loading more than once; the buttons go in only once */
    if (window.openrndrRunButtons) return;
    window.openrndrRunButtons = true;

    function runProgram(payload) {
        RUN_PROGRAM
    }

    /* The guide hides the import lines of its code blocks, and keeps the whole code in data-raw-code */
    function programCode(code) {
        return code.getAttribute('data-raw-code') || code.textContent;
    }

    /*
     * The guide's theme styles every button in a code block like its copy button (top right, shown on hover);
     * this one goes just left of it. The copy button is .75rem wide with .75rem padding on each side.
     */
    const style = document.createElement('style');
    style.textContent =
        'div.highlighter-rouge > button.openrndr-run { right: 2.25rem; }' +
        'div.highlighter-rouge:hover > button.openrndr-run { cursor: pointer; }' +
        'div.highlighter-rouge > button.openrndr-run svg { fill: #2ea043; }';
    document.head.appendChild(style);

    const blocks = document.querySelectorAll('div.language-kotlin.highlighter-rouge');
    blocks.forEach(function (block, i) {
        const code = block.querySelector('code');
        if (!code || !/\bfun\s+main\s*\(/.test(programCode(code))) return;

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'openrndr-run';
        button.dataset.index = String(i + 1);
        button.ariaLabel = 'Run this program in the IDE';
        button.title = 'Run this program in the IDE';
        button.innerHTML = '<svg viewBox="0 0 24 24"><path d="M6 3.5v17l14-8.5z"/></svg>';
        block.appendChild(button);
    });

    /*
     * The guide takes every click on a button in a Kotlin code block as a click on its copy button, and stops it
     * there (a capturing click listener on the document). A capturing listener on the window runs before that one,
     * so the run buttons' clicks are handled here and kept from the guide.
     */
    window.addEventListener('click', function (e) {
        const button = e.target instanceof Element ? e.target.closest('button.openrndr-run') : null;
        if (!button) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        const code = button.parentElement.querySelector('code');
        runProgram(button.dataset.index + '\n' + document.title + '\n' + programCode(code));
    }, true);
})();
