/*
 * Injected into guide.openrndr.org pages by the OPENRNDR plugin (OpenrndrGuideBrowser) once they have loaded.
 * It lets the guide run programs in the IDE: the guide draws the run buttons itself (next to its copy buttons) and
 * calls window.openrndrIde.runProgram when one is clicked. This is all the plugin and the guide share, so the
 * plugin keeps working whatever site generator and markup the guide uses:
 *
 *   window.openrndrIde.version
 *       1. Raised only for changes the guide has to know about.
 *   window.openrndrIde.runProgram(index, title, code)
 *       index: the code block's position among the page's Kotlin code blocks, from 1; it names the copied file
 *       title: the page title (document.title); it names the copied file too
 *       code:  the whole program, including the imports the guide hides
 *   the 'openrndr-ide-ready' event on window
 *       Sent once window.openrndrIde is there. The guide should both check for openrndrIde and listen for the
 *       event, as its scripts may run before or after this one. It may come more than once for a page.
 *
 * The plugin fills in the body of send with a call into the IDE that receives the three values, one per line.
 */
(function () {
    function send(payload) {
        RUN_PROGRAM
    }

    window.openrndrIde = {
        version: 1,
        runProgram: function (index, title, code) {
            send(String(index) + '\n' + String(title).replace(/[\r\n]+/g, ' ') + '\n' + String(code));
        }
    };
    window.dispatchEvent(new Event('openrndr-ide-ready'));
})();
