package io.github.tophtab.lspstore

import io.github.libxposed.api.XposedModule

/**
 * LSP Store declares itself as an Xposed module for exactly one reason: the
 * module repository only lists APKs that carry a module declaration, and being
 * listed is what lets LSPosed Manager install and update the store.
 *
 * It hooks nothing. The scope is LSP Store itself (`META-INF/xposed/scope.list`),
 * so even when a user enables it, the framework only loads this empty class into
 * the store's own process and no other app is touched. Every feature works
 * without the module being activated.
 */
class LspStoreModule : XposedModule()
